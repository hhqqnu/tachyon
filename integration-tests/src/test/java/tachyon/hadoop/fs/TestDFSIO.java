/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.hadoop.fs;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.util.Date;
import java.util.Random;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PositionedReadable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tachyon.Constants;
import tachyon.conf.TachyonConf;
import tachyon.hadoop.ConfUtils;
import tachyon.hadoop.TFS;
import tachyon.master.LocalTachyonCluster;

/**
 * Distributed i/o benchmark.
 * <p>
 * This test writes into or reads from a specified number of files. Number of bytes to write or read
 * is specified as a parameter to the test. Each file is accessed in a separate map task.
 * <p>
 * The reducer collects the following statistics:
 * <ul>
 * <li>number of tasks completed</li>
 * <li>number of bytes written/read</li>
 * <li>execution time</li>
 * <li>io rate</li>
 * <li>io rate squared</li>
 * </ul>
 *
 * Finally, the following information is appended to a local file
 * <ul>
 * <li>read or write test</li>
 * <li>date and time the test finished</li>
 * <li>number of files</li>
 * <li>total number of bytes processed</li>
 * <li>throughput in mb/sec (total number of bytes / sum of processing times)</li>
 * <li>average i/o rate in mb/sec per file</li>
 * <li>standard deviation of i/o rate</li>
 * </ul>
 */
public class TestDFSIO implements Tool {
  // Constants for TestDFSIO
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);
  private static final int DEFAULT_BUFFER_SIZE = 4096;
  private static final String BASE_FILE_NAME = "test_io_";
  private static final String DEFAULT_RES_FILE_NAME = "TestDFSIO_results.log";
  private static final long MEGA = ByteMultiple.MB.value();
  private static final int DEFAULT_NR_BYTES = 16384;
  private static final int DEFAULT_NR_FILES = 4;
  private static boolean sGenerateReportFile = false;
  private static final String USAGE = "Usage: " + TestDFSIO.class.getSimpleName()
      + " [genericOptions]" + " -read [-random | -backward | -skip [-skipSize Size]] |"
      + " -write | -append | -clean" + " [-compression codecClassName]" + " [-nrFiles N]"
      + " [-size Size[B|KB|MB|GB|TB]]" + " [-resFile resultFileName] [-bufferSize Bytes]"
      + " [-rootDir]";

  // Constants for Tachyon
  private static final int BLOCK_SIZE = 8192;
  private Configuration mConfig;
  private static LocalTachyonCluster sLocalTachyonCluster = null;
  private static URI sLocalTachyonClusterUri = null;

  static {
    Configuration.addDefaultResource("hdfs-default.xml");
    Configuration.addDefaultResource("hdfs-site.xml");
    Configuration.addDefaultResource("mapred-default.xml");
    Configuration.addDefaultResource("mapred-site.xml");
  }

  private static enum TestType {
    TEST_TYPE_READ("read"), TEST_TYPE_WRITE("write"), TEST_TYPE_CLEANUP("cleanup"),
        TEST_TYPE_APPEND("append"), TEST_TYPE_READ_RANDOM("random read"),
        TEST_TYPE_READ_BACKWARD("backward read"), TEST_TYPE_READ_SKIP("skip read");

    private String mType;

    private TestType(String t) {
      mType = t;
    }

    @Override
    // String
    public String toString() {
      return mType;
    }
  }

  static enum ByteMultiple {
    B(1L), KB(0x400L), MB(0x100000L), GB(0x40000000L), TB(0x10000000000L);

    private long mMultiplier;

    private ByteMultiple(long mult) {
      mMultiplier = mult;
    }

    long value() {
      return mMultiplier;
    }

    static ByteMultiple parseString(String sMultiple) {
      if (sMultiple == null || sMultiple.isEmpty()) { // MB by default
        return MB;
      }
      String sMU = sMultiple.toUpperCase();
      if (B.name().toUpperCase().endsWith(sMU)) {
        return B;
      }
      if (KB.name().toUpperCase().endsWith(sMU)) {
        return KB;
      }
      if (MB.name().toUpperCase().endsWith(sMU)) {
        return MB;
      }
      if (GB.name().toUpperCase().endsWith(sMU)) {
        return GB;
      }
      if (TB.name().toUpperCase().endsWith(sMU)) {
        return TB;
      }
      throw new IllegalArgumentException("Unsupported ByteMultiple " + sMultiple);
    }
  }

  public TestDFSIO() {
    this.mConfig = new Configuration();
  }

  private static String getBaseDir(Configuration conf) {
    return conf.get("test.dfsio.build.data", "/benchmarks/TestDFSIO");
  }

  private static Path getControlDir(Configuration conf) {
    return new Path(getBaseDir(conf), "io_control");
  }

  private static Path getWriteDir(Configuration conf) {
    return new Path(getBaseDir(conf), "io_write");
  }

  private static Path getReadDir(Configuration conf) {
    return new Path(getBaseDir(conf), "io_read");
  }

  private static Path getAppendDir(Configuration conf) {
    return new Path(getBaseDir(conf), "io_append");
  }

  private static Path getRandomReadDir(Configuration conf) {
    return new Path(getBaseDir(conf), "io_random_read");
  }

  private static Path getDataDir(Configuration conf) {
    return new Path(getBaseDir(conf), "io_data");
  }

  private static TestDFSIO sBench;

  @BeforeClass
  public static void beforeClass() throws Exception {
    // Init TestDFSIO
    sBench = new TestDFSIO();
    sBench.getConf().setBoolean("dfs.support.append", true);

    // Start local Tachyon cluster
    sLocalTachyonCluster = new LocalTachyonCluster(500000, 100000, BLOCK_SIZE);
    sLocalTachyonCluster.start();

    sLocalTachyonClusterUri = URI.create(sLocalTachyonCluster.getMasterUri());
    sBench.getConf().set("fs.defaultFS", sLocalTachyonClusterUri.toString());
    sBench.getConf().set("fs.default.name", sLocalTachyonClusterUri.toString());
    sBench.getConf().set("fs." + Constants.SCHEME + ".impl", TFS.class.getName());

    // Store TachyonConf in Hadoop Configuration
    TachyonConf tachyonConf = sLocalTachyonCluster.getMasterTachyonConf();
    ConfUtils.storeToHadoopConfiguration(tachyonConf, sBench.getConf());

    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    sBench.createControlFile(fs, DEFAULT_NR_BYTES, DEFAULT_NR_FILES);

    /** Check write here, as it is required for other tests */
    testWrite();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    if (sLocalTachyonCluster == null) {
      return;
    }

    // Clear TestDFSIO
    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    sBench.cleanup(fs);

    // Stop local Tachyon cluster
    sLocalTachyonCluster.stop();
  }

  public static void testWrite() throws Exception {
    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    long tStart = System.currentTimeMillis();
    sBench.writeTest(fs);
    long execTime = System.currentTimeMillis() - tStart;
    sBench.analyzeResult(fs, TestType.TEST_TYPE_WRITE, execTime);
  }

  @Test(timeout = 25000)
  public void testRead() throws Exception {
    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    long tStart = System.currentTimeMillis();
    sBench.readTest(fs);
    long execTime = System.currentTimeMillis() - tStart;
    sBench.analyzeResult(fs, TestType.TEST_TYPE_READ, execTime);
  }

  @Test(timeout = 25000)
  public void testReadRandom() throws Exception {
    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    long tStart = System.currentTimeMillis();
    sBench.getConf().setLong("test.io.skip.size", 0);
    sBench.randomReadTest(fs);
    long execTime = System.currentTimeMillis() - tStart;
    sBench.analyzeResult(fs, TestType.TEST_TYPE_READ_RANDOM, execTime);
  }

  @Test(timeout = 25000)
  public void testReadBackward() throws Exception {
    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    long tStart = System.currentTimeMillis();
    sBench.getConf().setLong("test.io.skip.size", -DEFAULT_BUFFER_SIZE);
    sBench.randomReadTest(fs);
    long execTime = System.currentTimeMillis() - tStart;
    sBench.analyzeResult(fs, TestType.TEST_TYPE_READ_BACKWARD, execTime);
  }

  @Test(timeout = 20000)
  public void testReadSkip() throws Exception {
    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    long tStart = System.currentTimeMillis();
    sBench.getConf().setLong("test.io.skip.size", 1);
    sBench.randomReadTest(fs);
    long execTime = System.currentTimeMillis() - tStart;
    sBench.analyzeResult(fs, TestType.TEST_TYPE_READ_SKIP, execTime);
  }

  @Test(timeout = 25000)
  public void testReadLargeSkip() throws Exception {
    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    long tStart = System.currentTimeMillis();
    sBench.getConf().setLong("test.io.skip.size", 5000);
    sBench.randomReadTest(fs);
    long execTime = System.currentTimeMillis() - tStart;
    sBench.analyzeResult(fs, TestType.TEST_TYPE_READ_SKIP, execTime);
  }

  // TODO: Should active this unit test after TACHYON-25 has been solved
  // @Test (timeout = 25000)
  public void testAppend() throws Exception {
    FileSystem fs = FileSystem.get(sLocalTachyonClusterUri, sBench.getConf());
    long tStart = System.currentTimeMillis();
    sBench.appendTest(fs);
    long execTime = System.currentTimeMillis() - tStart;
    sBench.analyzeResult(fs, TestType.TEST_TYPE_APPEND, execTime);
  }

  @SuppressWarnings("deprecation")
  private void createControlFile(FileSystem fs, long nrBytes, // in bytes
      int nrFiles) throws IOException {
    LOG.info("creating control file: " + nrBytes + " bytes, " + nrFiles + " files");

    Path controlDir = getControlDir(mConfig);

    if (!fs.exists(controlDir)) {

      fs.delete(controlDir, true);

      for (int i = 0; i < nrFiles; i ++) {
        String name = getFileName(i);
        Path controlFile = new Path(controlDir, "in_file_" + name);
        SequenceFile.Writer writer = null;
        try {
          writer =
              SequenceFile.createWriter(fs, mConfig, controlFile, Text.class, LongWritable.class,
                  CompressionType.NONE);
          writer.append(new Text(name), new LongWritable(nrBytes));
        } catch (Exception e) {
          throw new IOException(e.getLocalizedMessage());
        } finally {
          if (writer != null) {
            writer.close();
          }
          writer = null;
        }
      }
    }
    LOG.info("created control files for: " + nrFiles + " files");
  }

  private static String getFileName(int fIdx) {
    return BASE_FILE_NAME + Integer.toString(fIdx);
  }

  /**
   * Write/Read mapper base class.
   * <p>
   * Collects the following statistics per task:
   * <ul>
   * <li>number of tasks completed</li>
   * <li>number of bytes written/read</li>
   * <li>execution time</li>
   * <li>i/o rate</li>
   * <li>i/o rate squared</li>
   * </ul>
   */
  private abstract static class IOStatMapper extends IOMapperBase<Long> {
    protected CompressionCodec mCompressionCodec;

    IOStatMapper() {}

    @Override
    // Mapper
    public void configure(JobConf conf) {
      super.configure(conf);

      // grab compression
      String compression = getConf().get("test.io.compression.class", null);
      Class<? extends CompressionCodec> codec;

      // try to initialize codec
      try {
        codec =
            (compression == null) ? null : Class.forName(compression).asSubclass(
                CompressionCodec.class);
      } catch (Exception e) {
        throw new RuntimeException("Compression codec not found: ", e);
      }

      if (codec != null) {
        mCompressionCodec = (CompressionCodec) ReflectionUtils.newInstance(codec, getConf());
      }
    }

    @Override
    // IOMapperBase
    void collectStats(OutputCollector<Text, Text> output, String name, long execTime, Long objSize)
        throws IOException {
      long totalSize = objSize.longValue();
      float ioRateMbSec = (float) totalSize * 1000 / (execTime * MEGA);
      LOG.info("Number of bytes processed = " + totalSize);
      LOG.info("Exec time = " + execTime);
      LOG.info("IO rate = " + ioRateMbSec);

      output.collect(new Text(AccumulatingReducer.VALUE_TYPE_LONG + "tasks"),
          new Text(String.valueOf(1)));
      output.collect(new Text(AccumulatingReducer.VALUE_TYPE_LONG + "size"),
          new Text(String.valueOf(totalSize)));
      output.collect(new Text(AccumulatingReducer.VALUE_TYPE_LONG + "time"),
          new Text(String.valueOf(execTime)));
      output.collect(new Text(AccumulatingReducer.VALUE_TYPE_FLOAT + "rate"),
          new Text(String.valueOf(ioRateMbSec * 1000)));
      output.collect(new Text(AccumulatingReducer.VALUE_TYPE_FLOAT + "sqrate"),
          new Text(String.valueOf(ioRateMbSec * ioRateMbSec * 1000)));
    }
  }

  /**
   * Write mapper class.
   */
  public static class WriteMapper extends IOStatMapper {

    public WriteMapper() {
      for (int i = 0; i < mBufferSize; i ++) {
        mBuffer[i] = (byte) ('0' + i % 50);
      }
    }

    @Override
    // IOMapperBase
    public Closeable getIOStream(String name) throws IOException {
      // create file
      OutputStream out = mFS.create(new Path(getDataDir(getConf()), name), true, mBufferSize);
      if (mCompressionCodec != null) {
        out = mCompressionCodec.createOutputStream(out);
      }
      LOG.info("out = " + out.getClass().getName());
      return out;
    }

    @Override
    // IOMapperBase, totalSize is in bytes
    public Long doIO(Reporter reporter, String name, long totalSize) throws IOException {
      OutputStream out = (OutputStream) this.mStream;
      // write to the file
      long nrRemaining;
      for (nrRemaining = totalSize; nrRemaining > 0; nrRemaining -= mBufferSize) {
        int curSize = (mBufferSize < nrRemaining) ? mBufferSize : (int) nrRemaining;
        out.write(mBuffer, 0, curSize);
        reporter.setStatus("writing " + name + "@" + (totalSize - nrRemaining) + "/" + totalSize
            + " ::host = " + mHostname);
      }
      return Long.valueOf(totalSize);
    }
  }

  private void writeTest(FileSystem fs) throws IOException {
    Path writeDir = getWriteDir(mConfig);
    fs.delete(getDataDir(mConfig), true);
    fs.delete(writeDir, true);

    runIOTest(WriteMapper.class, writeDir);
  }

  private void runIOTest(Class<? extends Mapper<Text, LongWritable, Text, Text>> mapperClass,
      Path outputDir) throws IOException {
    JobConf job = new JobConf(mConfig, TestDFSIO.class);

    FileInputFormat.setInputPaths(job, getControlDir(mConfig));
    job.setInputFormat(SequenceFileInputFormat.class);

    job.setMapperClass(mapperClass);
    job.setReducerClass(AccumulatingReducer.class);

    FileOutputFormat.setOutputPath(job, outputDir);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(Text.class);
    job.setNumReduceTasks(1);
    JobClient.runJob(job);
  }

  /**
   * Append mapper class.
   */
  public static class AppendMapper extends IOStatMapper {

    public AppendMapper() {
      for (int i = 0; i < mBufferSize; i ++) {
        mBuffer[i] = (byte) ('0' + i % 50);
      }
    }

    @Override
    // IOMapperBase
    public Closeable getIOStream(String name) throws IOException {
      // open file for append
      OutputStream out = mFS.append(new Path(getDataDir(getConf()), name), mBufferSize);
      if (mCompressionCodec != null) {
        out = mCompressionCodec.createOutputStream(out);
      }
      LOG.info("out = " + out.getClass().getName());
      return out;
    }

    @Override
    // IOMapperBase, totalSize is in Bytes
    public Long doIO(Reporter reporter, String name, long totalSize) throws IOException {
      OutputStream out = (OutputStream) this.mStream;
      // write to the file
      long nrRemaining;
      for (nrRemaining = totalSize; nrRemaining > 0; nrRemaining -= mBufferSize) {
        int curSize = (mBufferSize < nrRemaining) ? mBufferSize : (int) nrRemaining;
        out.write(mBuffer, 0, curSize);
        reporter.setStatus("writing " + name + "@" + (totalSize - nrRemaining) + "/" + totalSize
            + " ::host = " + mHostname);
      }
      return Long.valueOf(totalSize);
    }
  }

  private void appendTest(FileSystem fs) throws IOException {
    Path appendDir = getAppendDir(mConfig);
    fs.delete(appendDir, true);
    runIOTest(AppendMapper.class, appendDir);
  }

  /**
   * Read mapper class.
   */
  public static class ReadMapper extends IOStatMapper {

    public ReadMapper() {}

    @Override
    // IOMapperBase
    public Closeable getIOStream(String name) throws IOException {
      // open file
      InputStream in = mFS.open(new Path(getDataDir(getConf()), name));
      if (mCompressionCodec != null) {
        in = mCompressionCodec.createInputStream(in);
      }
      LOG.info("in = " + in.getClass().getName());
      return in;
    }

    @Override
    // IOMapperBase, totalSize in Bytes
    public Long doIO(Reporter reporter, String name, long totalSize) throws IOException {
      InputStream in = (InputStream) this.mStream;
      long actualSize = 0;
      while (actualSize < totalSize) {
        int curSize = in.read(mBuffer, 0, mBufferSize);
        if (curSize < 0) {
          break;
        }
        actualSize += curSize;
        reporter.setStatus("reading " + name + "@" + actualSize + "/" + totalSize + " ::host = "
            + mHostname);
      }
      return Long.valueOf(actualSize);
    }
  }

  private void readTest(FileSystem fs) throws IOException {
    Path readDir = getReadDir(mConfig);
    fs.delete(readDir, true);
    runIOTest(ReadMapper.class, readDir);
  }

  /**
   * Mapper class for random reads. The mapper chooses a position in the file and reads bufferSize
   * bytes starting at the chosen position. It stops after reading the totalSize bytes, specified
   * by size.
   *
   * There are three type of reads. 1) Random read always chooses a random position to read from:
   * skipSize = 0 2) Backward read reads file in reverse order : skipSize < 0 3) Skip-read skips
   * skipSize bytes after every read : skipSize > 0
   */
  public static class RandomReadMapper extends IOStatMapper {
    private Random mRnd;
    private long mFileSize;
    private long mSkipSize;

    @Override
    // Mapper
    public void configure(JobConf conf) {
      super.configure(conf);
      mSkipSize = conf.getLong("test.io.skip.size", 0);
    }

    public RandomReadMapper() {
      mRnd = new Random();
    }

    @Override
    // IOMapperBase
    public Closeable getIOStream(String name) throws IOException {
      Path filePath = new Path(getDataDir(getConf()), name);
      this.mFileSize = mFS.getFileStatus(filePath).getLen();
      InputStream in = mFS.open(filePath);
      if (mCompressionCodec != null) {
        in = new FSDataInputStream(mCompressionCodec.createInputStream(in));
      }
      LOG.info("in = " + in.getClass().getName());
      LOG.info("skipSize = " + mSkipSize);
      return in;
    }

    @Override
    // IOMapperBase, totalSize in Bytes
    public Long doIO(Reporter reporter, String name, long totalSize) throws IOException {
      PositionedReadable in = (PositionedReadable) this.mStream;
      long actualSize = 0;
      for (long pos = nextOffset(-1); actualSize < totalSize; pos = nextOffset(pos)) {
        int curSize = in.read(pos, mBuffer, 0, mBufferSize);
        if (curSize < 0) {
          break;
        }
        actualSize += curSize;
        reporter.setStatus("reading " + name + "@" + actualSize + "/" + totalSize + " ::host = "
            + mHostname);
      }
      return Long.valueOf(actualSize);
    }

    /**
     * Get next offset for reading. If current < 0 then choose initial offset according to the read
     * type.
     *
     * @param current offset
     * @return
     */
    private long nextOffset(long current) {
      if (mSkipSize == 0) {
        return mRnd.nextInt((int) (mFileSize));
      }
      if (mSkipSize > 0) {
        return (current < 0) ? 0 : (current + mBufferSize + mSkipSize);
      }
      // skipSize < 0
      return (current < 0) ? Math.max(0, mFileSize - mBufferSize) : Math
          .max(0, current + mSkipSize);
    }
  }

  private void randomReadTest(FileSystem fs) throws IOException {
    Path readDir = getRandomReadDir(mConfig);
    fs.delete(readDir, true);
    runIOTest(RandomReadMapper.class, readDir);
  }

  // fileSize is in Bytes
  private void sequentialTest(FileSystem fs, TestType testType, long fileSize, int nrFiles)
      throws IOException {
    IOStatMapper ioer = null;
    switch (testType) {
      case TEST_TYPE_READ:
        ioer = new ReadMapper();
        break;
      case TEST_TYPE_WRITE:
        ioer = new WriteMapper();
        break;
      case TEST_TYPE_APPEND:
        ioer = new AppendMapper();
        break;
      case TEST_TYPE_READ_RANDOM:
      case TEST_TYPE_READ_BACKWARD:
      case TEST_TYPE_READ_SKIP:
        ioer = new RandomReadMapper();
        break;
      default:
        return;
    }
    for (int i = 0; i < nrFiles; i ++) {
      ioer.doIO(Reporter.NULL, BASE_FILE_NAME + Integer.toString(i), fileSize);
    }
  }

  public static void main(String[] args) {
    TestDFSIO bench = new TestDFSIO();
    int res = -1;
    try {
      res = ToolRunner.run(bench, args);
    } catch (Exception e) {
      System.err.print(StringUtils.stringifyException(e));
      res = -2;
    }
    if (res == -1) {
      System.err.print(USAGE);
    }
    System.exit(res);
  }

  @Override
  // Tool
  public int run(String[] args) throws IOException {
    TestType testType = null;
    int bufferSize = DEFAULT_BUFFER_SIZE;
    long nrBytes = 1 * MEGA;
    int nrFiles = 1;
    long skipSize = 0;
    String resFileName = DEFAULT_RES_FILE_NAME;
    String compressionClass = null;
    boolean isSequential = false;
    String version = TestDFSIO.class.getSimpleName() + ".1.7";
    sGenerateReportFile = true;

    LOG.info(version);
    if (args.length == 0) {
      System.err.println("Missing arguments.");
      return -1;
    }

    for (int i = 0; i < args.length; i ++) { // parse command line
      if (args[i].startsWith("-read")) {
        testType = TestType.TEST_TYPE_READ;
      } else if (args[i].equals("-write")) {
        testType = TestType.TEST_TYPE_WRITE;
      } else if (args[i].equals("-append")) {
        testType = TestType.TEST_TYPE_APPEND;
      } else if (args[i].equals("-random")) {
        if (testType != TestType.TEST_TYPE_READ) {
          return -1;
        }
        testType = TestType.TEST_TYPE_READ_RANDOM;
      } else if (args[i].equals("-backward")) {
        if (testType != TestType.TEST_TYPE_READ) {
          return -1;
        }
        testType = TestType.TEST_TYPE_READ_BACKWARD;
      } else if (args[i].equals("-skip")) {
        if (testType != TestType.TEST_TYPE_READ) {
          return -1;
        }
        testType = TestType.TEST_TYPE_READ_SKIP;
      } else if (args[i].equals("-clean")) {
        testType = TestType.TEST_TYPE_CLEANUP;
      } else if (args[i].startsWith("-seq")) {
        isSequential = true;
      } else if (args[i].startsWith("-compression")) {
        compressionClass = args[++i];
      } else if (args[i].equals("-nrFiles")) {
        nrFiles = Integer.parseInt(args[++i]);
      } else if (args[i].equals("-fileSize") || args[i].equals("-size")) {
        nrBytes = parseSize(args[++i]);
      } else if (args[i].equals("-skipSize")) {
        skipSize = parseSize(args[++i]);
      } else if (args[i].equals("-bufferSize")) {
        bufferSize = Integer.parseInt(args[++i]);
      } else if (args[i].equals("-resFile")) {
        resFileName = args[++i];
      } else {
        System.err.println("Illegal argument: " + args[i]);
        return -1;
      }
    }
    if (testType == null) {
      return -1;
    }
    if (testType == TestType.TEST_TYPE_READ_BACKWARD) {
      skipSize = -bufferSize;
    } else if (testType == TestType.TEST_TYPE_READ_SKIP && skipSize == 0) {
      skipSize = bufferSize;
    }

    LOG.info("nrFiles = " + nrFiles);
    LOG.info("nrBytes (MB) = " + toMB(nrBytes));
    LOG.info("bufferSize = " + bufferSize);
    if (skipSize > 0) {
      LOG.info("skipSize = " + skipSize);
    }
    LOG.info("baseDir = " + getBaseDir(mConfig));

    if (compressionClass != null) {
      mConfig.set("test.io.compression.class", compressionClass);
      LOG.info("compressionClass = " + compressionClass);
    }

    mConfig.setInt("test.io.file.buffer.size", bufferSize);
    mConfig.setLong("test.io.skip.size", skipSize);
    mConfig.setBoolean("dfs.support.append", true);
    FileSystem fs = FileSystem.get(mConfig);

    if (isSequential) {
      long tStart = System.currentTimeMillis();
      sequentialTest(fs, testType, nrBytes, nrFiles);
      long execTime = System.currentTimeMillis() - tStart;
      String resultLine = "Seq Test exec time sec: " + (float) execTime / 1000;
      LOG.info(resultLine);
      return 0;
    }
    if (testType == TestType.TEST_TYPE_CLEANUP) {
      cleanup(fs);
      return 0;
    }
    createControlFile(fs, nrBytes, nrFiles);
    long tStart = System.currentTimeMillis();
    switch (testType) {
      case TEST_TYPE_WRITE:
        writeTest(fs);
        break;
      case TEST_TYPE_READ:
        readTest(fs);
        break;
      case TEST_TYPE_APPEND:
        appendTest(fs);
        break;
      case TEST_TYPE_READ_RANDOM:
      case TEST_TYPE_READ_BACKWARD:
      case TEST_TYPE_READ_SKIP:
        randomReadTest(fs);
        break;
      default:
    }
    long execTime = System.currentTimeMillis() - tStart;

    analyzeResult(fs, testType, execTime, resFileName);
    return 0;
  }

  @Override
  // Configurable
  public Configuration getConf() {
    return this.mConfig;
  }

  @Override
  // Configurable
  public void setConf(Configuration conf) {
    this.mConfig = conf;
  }

  /**
   * Returns size in bytes.
   *
   * @param arg = {d}[B|KB|MB|GB|TB]
   * @return
   */
  static long parseSize(String arg) {
    String[] args = arg.split("\\D", 2); // get digits
    assert args.length <= 2;
    long nrBytes = Long.parseLong(args[0]);
    String bytesMult = arg.substring(args[0].length()); // get byte multiple
    return nrBytes * ByteMultiple.parseString(bytesMult).value();
  }

  static float toMB(long bytes) {
    return ((float) bytes) / MEGA;
  }

  private void analyzeResult(FileSystem fs, TestType testType, long execTime, String resFileName)
      throws IOException {
    Path reduceFile = getReduceFilePath(testType);
    long tasks = 0;
    long size = 0;
    long time = 0;
    float rate = 0;
    float sqrate = 0;
    DataInputStream in = null;
    BufferedReader lines = null;
    try {
      in = new DataInputStream(fs.open(reduceFile));
      lines = new BufferedReader(new InputStreamReader(in));
      String line;
      while ((line = lines.readLine()) != null) {
        StringTokenizer tokens = new StringTokenizer(line, " \t\n\r\f%");
        String attr = tokens.nextToken();
        if (attr.endsWith(":tasks")) {
          tasks = Long.parseLong(tokens.nextToken());
        } else if (attr.endsWith(":size")) {
          size = Long.parseLong(tokens.nextToken());
        } else if (attr.endsWith(":time")) {
          time = Long.parseLong(tokens.nextToken());
        } else if (attr.endsWith(":rate")) {
          rate = Float.parseFloat(tokens.nextToken());
        } else if (attr.endsWith(":sqrate")) {
          sqrate = Float.parseFloat(tokens.nextToken());
        }
      }
    } finally {
      if (in != null) {
        in.close();
      }
      if (lines != null) {
        lines.close();
      }
    }

    double med = rate / 1000 / tasks;
    double stdDev = Math.sqrt(Math.abs(sqrate / 1000 / tasks - med * med));
    String[] resultLines =
        {"----- TestDFSIO ----- : " + testType,
            "           Date & time: " + new Date(System.currentTimeMillis()),
            "       Number of files: " + tasks, "Total MBytes processed: " + toMB(size),
            "     Throughput mb/sec: " + size * 1000.0 / (time * MEGA),
            "Average IO rate mb/sec: " + med, " IO rate std deviation: " + stdDev,
            "    Test exec time sec: " + (float) execTime / 1000, ""};

    PrintStream res = null;
    try {
      if (sGenerateReportFile) {
        res = new PrintStream(new FileOutputStream(new File(resFileName), true));
      }
      for (int i = 0; i < resultLines.length; i ++) {
        LOG.info(resultLines[i]);
        if (sGenerateReportFile) {
          res.println(resultLines[i]);
        } else {
          System.out.println(resultLines[i]);
        }
      }
    } finally {
      if (res != null) {
        res.close();
      }
    }
  }

  private void analyzeResult(FileSystem fs, TestType testType, long execTime) throws IOException {
    analyzeResult(fs, testType, execTime, DEFAULT_RES_FILE_NAME);
  }

  private Path getReduceFilePath(TestType testType) {
    switch (testType) {
      case TEST_TYPE_WRITE:
        return new Path(getWriteDir(mConfig), "part-00000");
      case TEST_TYPE_APPEND:
        return new Path(getAppendDir(mConfig), "part-00000");
      case TEST_TYPE_READ:
        return new Path(getReadDir(mConfig), "part-00000");
      case TEST_TYPE_READ_RANDOM:
      case TEST_TYPE_READ_BACKWARD:
      case TEST_TYPE_READ_SKIP:
        return new Path(getRandomReadDir(mConfig), "part-00000");
      default:
    }
    return null;
  }

  private void cleanup(FileSystem fs) throws IOException {
    LOG.info("Cleaning up test files");
    fs.delete(new Path(getBaseDir(mConfig)), true);
  }
}
