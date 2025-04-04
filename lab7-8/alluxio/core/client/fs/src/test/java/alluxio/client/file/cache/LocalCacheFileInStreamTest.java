/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.file.cache;

import static org.junit.Assert.fail;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.client.file.CacheContext;
import alluxio.client.file.FileInStream;
import alluxio.client.file.FileOutStream;
import alluxio.client.file.FileSystem;
import alluxio.client.file.ListStatusPartialResult;
import alluxio.client.file.MockFileInStream;
import alluxio.client.file.URIStatus;
import alluxio.client.file.cache.context.CachePerThreadContext;
import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.Configuration;
import alluxio.conf.InstancedConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AlluxioException;
import alluxio.exception.DirectoryNotEmptyException;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.FileIncompleteException;
import alluxio.exception.InvalidPathException;
import alluxio.exception.OpenDirectoryException;
import alluxio.file.ReadTargetBuffer;
import alluxio.grpc.CancelSyncMetadataPResponse;
import alluxio.grpc.CheckAccessPOptions;
import alluxio.grpc.CreateDirectoryPOptions;
import alluxio.grpc.CreateFilePOptions;
import alluxio.grpc.DeletePOptions;
import alluxio.grpc.ExistsPOptions;
import alluxio.grpc.FreePOptions;
import alluxio.grpc.GetStatusPOptions;
import alluxio.grpc.GetSyncProgressPResponse;
import alluxio.grpc.JobProgressReportFormat;
import alluxio.grpc.ListStatusPOptions;
import alluxio.grpc.ListStatusPartialPOptions;
import alluxio.grpc.MountPOptions;
import alluxio.grpc.OpenFilePOptions;
import alluxio.grpc.RenamePOptions;
import alluxio.grpc.ScheduleAsyncPersistencePOptions;
import alluxio.grpc.SetAclAction;
import alluxio.grpc.SetAclPOptions;
import alluxio.grpc.SetAttributePOptions;
import alluxio.grpc.SyncMetadataAsyncPResponse;
import alluxio.grpc.SyncMetadataPOptions;
import alluxio.grpc.SyncMetadataPResponse;
import alluxio.grpc.UnmountPOptions;
import alluxio.job.JobDescription;
import alluxio.job.JobRequest;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;
import alluxio.network.protocol.databuffer.DataFileChannel;
import alluxio.security.authorization.AclEntry;
import alluxio.util.io.BufferUtils;
import alluxio.util.io.PathUtils;
import alluxio.wire.BlockLocationInfo;
import alluxio.wire.FileInfo;
import alluxio.wire.MountPointInfo;
import alluxio.wire.SyncPointInfo;

import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Unit tests for {@link LocalCacheFileInStream}.
 */
@RunWith(Parameterized.class)
public class LocalCacheFileInStreamTest {
  private static InstancedConfiguration sConf = Configuration.modifiableGlobal();

  @Parameters(name = "{index}: page_size({0}), in_stream_buffer_size({1})")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
        {Constants.MB, 0}, {Constants.MB, 8 * Constants.KB}
    });
  }

  @Parameter(0)
  public int mPageSize;

  @Parameter(1)
  public int mBufferSize;

  @Before
  public void before() {
    MetricsSystem.clearAllMetrics();
    sConf.set(PropertyKey.USER_CLIENT_CACHE_PAGE_SIZE, mPageSize);
    sConf.set(PropertyKey.USER_CLIENT_CACHE_IN_STREAM_BUFFER_SIZE, mBufferSize);
    CachePerThreadContext.get().setCacheEnabled(true);
  }

  @Test
  public void readFullPage() throws Exception {
    int fileSize = mPageSize;
    int bufferSize = fileSize;
    int pages = 1;
    verifyReadFullFile(fileSize, bufferSize, pages);
  }

  @Test
  public void readFullPageThroughReadByteBufferMethod() throws Exception {
    int fileSize = mPageSize;
    int bufferSize = fileSize;
    int pages = 1;
    verifyReadFullFileThroughReadByteBufferMethod(fileSize, bufferSize, pages);
  }

  @Test
  public void readSmallPage() throws Exception {
    int fileSize = mPageSize / 5;
    int bufferSize = fileSize;
    int pages = 1;
    verifyReadFullFile(fileSize, bufferSize, pages);
  }

  @Test
  public void readSmallPageThroughReadByteBufferMethod() throws Exception {
    int fileSize = mPageSize / 5;
    int bufferSize = fileSize;
    int pages = 1;
    verifyReadFullFileThroughReadByteBufferMethod(fileSize, bufferSize, pages);
  }

  @Test
  public void readEmptyFileThroughReadByteBuffer() throws Exception {
    int fileSize = 0;
    byte[] fileData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(fileData, manager);

    byte[] readData = new byte[fileSize];
    ByteBuffer buffer = ByteBuffer.wrap(readData);
    int totalBytesRead = stream.read(buffer, 0, fileSize + 1);
    Assert.assertEquals(-1, totalBytesRead);
  }

  @Test
  public void readThroughReadByteBuffer() throws Exception {
    int fileSize = 10;
    byte[] fileData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(fileData, manager);

    byte[] readData = new byte[fileSize];
    ByteBuffer buffer = ByteBuffer.wrap(readData);
    int totalBytesRead = stream.read(buffer, 0, fileSize + 1);
    Assert.assertEquals(fileSize, totalBytesRead);
  }

  @Test
  public void readPartialPage() throws Exception {
    int fileSize = mPageSize;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    int partialReadSize = fileSize / 5;
    int offset = fileSize / 5;

    // cache miss
    byte[] cacheMiss = new byte[partialReadSize];
    stream.seek(offset);
    Assert.assertEquals(partialReadSize, stream.read(cacheMiss));
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, offset, offset + partialReadSize), cacheMiss);
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(1, manager.mPagesCached);

    // cache hit
    byte[] cacheHit = new byte[partialReadSize];
    stream.seek(offset);
    Assert.assertEquals(partialReadSize, stream.read(cacheHit));
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, offset, offset + partialReadSize), cacheHit);
    Assert.assertEquals(1, manager.mPagesServed);
  }

  @Test
  public void readPartialPageThroughReadByteBufferMethod() throws Exception {
    int fileSize = mPageSize;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    int partialReadSize = fileSize / 5;
    int offset = fileSize / 5;

    // cache miss
    ByteBuffer cacheMissBuffer = ByteBuffer.wrap(new byte[partialReadSize]);
    stream.seek(offset);
    Assert.assertEquals(partialReadSize, stream.read(cacheMissBuffer));
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, offset, offset + partialReadSize),
        cacheMissBuffer.array());
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(1, manager.mPagesCached);

    // cache hit
    ByteBuffer cacheHitBuffer = ByteBuffer.wrap(new byte[partialReadSize]);
    stream.seek(offset);
    Assert.assertEquals(partialReadSize, stream.read(cacheHitBuffer));
    Assert.assertArrayEquals(Arrays.copyOfRange(testData, offset, offset + partialReadSize),
        cacheHitBuffer.array());
    Assert.assertEquals(1, manager.mPagesServed);
  }

  @Test
  public void readMultiPage() throws Exception {
    int pages = 2;
    int fileSize = mPageSize + 10;
    int bufferSize = fileSize;
    verifyReadFullFile(fileSize, bufferSize, pages);
  }

  @Test
  public void readMultiPageThroughReadByteBufferMethod() throws Exception {
    int pages = 2;
    int fileSize = mPageSize + 10;
    int bufferSize = fileSize;
    verifyReadFullFileThroughReadByteBufferMethod(fileSize, bufferSize, pages);
  }

  @Test
  public void readMultiPageMixed() throws Exception {
    int pages = 10;
    int fileSize = mPageSize * pages;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    // populate cache
    int pagesCached = 0;
    for (int i = 0; i < pages; i++) {
      stream.seek(mPageSize * i);
      if (ThreadLocalRandom.current().nextBoolean()) {
        Assert.assertEquals(testData[(i * mPageSize)], stream.read());
        pagesCached++;
      }
    }

    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(pagesCached, manager.mPagesCached);

    // sequential read
    stream.seek(0);
    byte[] fullRead = new byte[fileSize];
    Assert.assertEquals(fileSize, stream.read(fullRead));
    Assert.assertArrayEquals(testData, fullRead);
    Assert.assertEquals(pagesCached, manager.mPagesServed);
  }

  @Test
  public void readMultiPageMixedThroughReadByteBufferMethod() throws Exception {
    int pages = 10;
    int fileSize = mPageSize * pages;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    // populate cache
    int pagesCached = 0;
    for (int i = 0; i < pages; i++) {
      stream.seek(mPageSize * i);
      if (ThreadLocalRandom.current().nextBoolean()) {
        Assert.assertEquals(testData[(i * mPageSize)], stream.read());
        pagesCached++;
      }
    }

    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(pagesCached, manager.mPagesCached);

    // sequential read
    stream.seek(0);
    ByteBuffer fullReadBuf = ByteBuffer.wrap(new byte[fileSize]);
    Assert.assertEquals(fileSize, stream.read(fullReadBuf));
    Assert.assertArrayEquals(testData, fullReadBuf.array());
    Assert.assertEquals(pagesCached, manager.mPagesServed);
  }

  @Test
  public void readOversizedBuffer() throws Exception {
    int pages = 1;
    int fileSize = mPageSize;
    int bufferSize = fileSize * 2;
    verifyReadFullFile(fileSize, bufferSize, pages);
  }

  @Test
  public void readOversizedBufferThroughReadByteBufferMethod() throws Exception {
    int pages = 1;
    int fileSize = mPageSize;
    int bufferSize = fileSize * 2;
    verifyReadFullFileThroughReadByteBufferMethod(fileSize, bufferSize, pages);
  }

  @Test
  public void readSmallPageOversizedBuffer() throws Exception {
    int pages = 1;
    int fileSize = mPageSize / 3;
    int bufferSize = fileSize * 2;
    verifyReadFullFile(fileSize, bufferSize, pages);
  }

  @Test
  public void readSmallPageOversizedBufferThroughReadByteBufferMethod() throws Exception {
    int pages = 1;
    int fileSize = mPageSize / 3;
    int bufferSize = fileSize * 2;
    verifyReadFullFileThroughReadByteBufferMethod(fileSize, bufferSize, pages);
  }

  @Test
  public void positionedReadPartialPage() throws Exception {
    int fileSize = mPageSize;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    int partialReadSize = fileSize / 5;
    int offset = fileSize / 5;

    // cache miss
    byte[] cacheMiss = new byte[partialReadSize];
    Assert.assertEquals(partialReadSize,
        stream.positionedRead(offset, cacheMiss, 0, cacheMiss.length));
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, offset, offset + partialReadSize), cacheMiss);
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(1, manager.mPagesCached);
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_EXTERNAL_REQUESTS.getName()).getCount());
    Assert.assertEquals(0,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_HIT_REQUESTS.getName()).getCount());

    // cache hit
    byte[] cacheHit = new byte[partialReadSize];
    Assert.assertEquals(partialReadSize,
        stream.positionedRead(offset, cacheHit, 0, cacheHit.length));
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, offset, offset + partialReadSize), cacheHit);
    Assert.assertEquals(1, manager.mPagesServed);
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_EXTERNAL_REQUESTS.getName()).getCount());
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_HIT_REQUESTS.getName()).getCount());
  }

  @Test
  public void positionReadOversizedBuffer() throws Exception {
    int fileSize = mPageSize;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    // cache miss
    byte[] cacheMiss = new byte[fileSize * 2];
    Assert.assertEquals(fileSize - 1,
        stream.positionedRead(1, cacheMiss, 2, fileSize * 2));
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, 1, fileSize - 1),
        Arrays.copyOfRange(cacheMiss, 2, fileSize));
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(1, manager.mPagesCached);

    // cache hit
    byte[] cacheHit = new byte[fileSize * 2];
    Assert.assertEquals(fileSize - 1,
        stream.positionedRead(1, cacheHit, 2, fileSize * 2));
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, 1, fileSize - 1),
        Arrays.copyOfRange(cacheHit, 2, fileSize));
    Assert.assertEquals(1, manager.mPagesServed);
  }

  @Test
  public void readPagesMetrics() throws Exception {
    int fileSize = mPageSize * 5;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    // cache miss
    int readSize = fileSize - 1;
    byte[] cacheMiss = new byte[readSize];
    stream.read(cacheMiss);
    Assert.assertEquals(0,
        MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_READ_CACHE.getName()).getCount());
    Assert.assertEquals(readSize, MetricsSystem.meter(
        MetricKey.CLIENT_CACHE_BYTES_REQUESTED_EXTERNAL.getName()).getCount());
    Assert.assertEquals(fileSize,
        MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_READ_EXTERNAL.getName()).getCount());
    Assert.assertEquals(5,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_EXTERNAL_REQUESTS.getName()).getCount());
    Assert.assertEquals(0,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_HIT_REQUESTS.getName()).getCount());

    // cache hit
    stream.read();
    Assert.assertEquals(readSize, MetricsSystem.meter(
        MetricKey.CLIENT_CACHE_BYTES_REQUESTED_EXTERNAL.getName()).getCount());
    Assert.assertEquals(fileSize,
        MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_READ_EXTERNAL.getName()).getCount());
    Assert.assertEquals(5,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_EXTERNAL_REQUESTS.getName()).getCount());
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_HIT_REQUESTS.getName()).getCount());
  }

  @Test
  public void externalStoreMultiRead() throws Exception {
    int fileSize = mPageSize;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    Map<AlluxioURI, byte[]> files = new HashMap<>();
    AlluxioURI testFilename = new AlluxioURI("/test");
    files.put(testFilename, testData);

    ByteArrayFileSystem fs = new MultiReadByteArrayFileSystem(files);

    LocalCacheFileInStream stream = new LocalCacheFileInStream(fs.getStatus(testFilename),
        (status) -> fs.openFile(status, OpenFilePOptions.getDefaultInstance()), manager, sConf,
        Optional.empty());

    // cache miss
    byte[] cacheMiss = new byte[fileSize];
    Assert.assertEquals(fileSize, stream.read(cacheMiss));
    Assert.assertArrayEquals(testData, cacheMiss);
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(1, manager.mPagesCached);

    // cache hit
    stream.seek(0);
    byte[] cacheHit = new byte[fileSize];
    Assert.assertEquals(fileSize, stream.read(cacheHit));
    Assert.assertArrayEquals(testData, cacheHit);
    Assert.assertEquals(1, manager.mPagesServed);
  }

  @Test
  public void externalStoreMultiReadThroughReadByteBufferMethod() throws Exception {
    int fileSize = mPageSize;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    Map<AlluxioURI, byte[]> files = new HashMap<>();
    AlluxioURI testFilename = new AlluxioURI("/test");
    files.put(testFilename, testData);

    ByteArrayFileSystem fs = new MultiReadByteArrayFileSystem(files);

    LocalCacheFileInStream stream = new LocalCacheFileInStream(fs.getStatus(testFilename),
        (status) -> fs.openFile(status, OpenFilePOptions.getDefaultInstance()), manager, sConf,
        Optional.empty());

    // cache miss
    ByteBuffer cacheMissBuf = ByteBuffer.wrap(new byte[fileSize]);
    Assert.assertEquals(fileSize, stream.read(cacheMissBuf));
    Assert.assertArrayEquals(testData, cacheMissBuf.array());
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(1, manager.mPagesCached);

    // cache hit
    stream.seek(0);
    ByteBuffer cacheHitBuf = ByteBuffer.wrap(new byte[fileSize]);
    Assert.assertEquals(fileSize, stream.read(cacheHitBuf));
    Assert.assertArrayEquals(testData, cacheHitBuf.array());
    Assert.assertEquals(1, manager.mPagesServed);
  }

  @Test
  public void readMultipleFiles() throws Exception {
    Random random = new Random();
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    Map<String, byte[]> files = IntStream.range(0, 10)
        .mapToObj(i -> BufferUtils.getIncreasingByteArray(i, random.nextInt(100)))
        .collect(Collectors.toMap(d -> PathUtils.uniqPath(), Function.identity()));
    Map<AlluxioURI, LocalCacheFileInStream> streams = setupWithMultipleFiles(files, manager);
    for (AlluxioURI path : streams.keySet()) {
      try (InputStream stream = streams.get(path)) {
        Assert.assertArrayEquals(files.get(path.toString()), ByteStreams.toByteArray(stream));
      }
    }
  }

  @Test
  public void cacheMetricCacheHitReadTime() throws Exception {
    byte[] testData = BufferUtils.getIncreasingByteArray(mPageSize);
    AlluxioURI testFileName = new AlluxioURI("/test");
    Map<AlluxioURI, byte[]> files = ImmutableMap.of(testFileName, testData);
    StepTicker timeSource = new StepTicker();
    TimedMockByteArrayCacheManager manager = new TimedMockByteArrayCacheManager(timeSource);
    Map<String, Long> recordedMetrics = new HashMap<>();
    TimedByteArrayFileSystem fs = new TimedByteArrayFileSystem(
        files,
        (name, value) -> recordedMetrics.compute(name, (k, v) -> v == null ? value : v + value),
        timeSource
    );
    LocalCacheFileInStream stream =
        new LocalCacheFileInStream(fs.getStatus(testFileName),
            (status) -> fs.openFile(status, OpenFilePOptions.getDefaultInstance()), manager,
            sConf, Optional.empty()) {
          @Override
          protected Stopwatch createUnstartedStopwatch() {
            return Stopwatch.createUnstarted(timeSource);
          }
        };

    Assert.assertArrayEquals(testData, ByteStreams.toByteArray(stream));
    long timeReadExternal = recordedMetrics.get(
        MetricKey.CLIENT_CACHE_PAGE_READ_EXTERNAL_TIME_NS.getMetricName());
    Assert.assertEquals(timeSource.get(StepTicker.Type.CACHE_MISS), timeReadExternal);
  }

  @Test
  public void testUnbuffer() throws Exception {
    int fileSize = mPageSize;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    int partialReadSize = fileSize / 5;
    int offset = fileSize / 5;

    byte[] cacheMiss = new byte[partialReadSize];
    stream.unbuffer();
    stream.seek(offset);
    stream.unbuffer();
    Assert.assertEquals(partialReadSize, stream.read(cacheMiss));
    stream.unbuffer();
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, offset, offset + partialReadSize), cacheMiss);
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(1, manager.mPagesCached);

    byte[] cacheHit = new byte[partialReadSize];
    stream.unbuffer();
    stream.seek(offset);
    stream.unbuffer();
    Assert.assertEquals(partialReadSize, stream.read(cacheHit));
    stream.unbuffer();
    Assert.assertArrayEquals(
        Arrays.copyOfRange(testData, offset, offset + partialReadSize), cacheHit);
    Assert.assertEquals(1, manager.mPagesServed);
  }

  @Test
  public void testPageDataFileCorrupted() throws Exception
  {
    int pages = 10;
    int fileSize = mPageSize * pages;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    //by default local cache fallback is not enabled, the read should fail for any error
    LocalCacheFileInStream streamWithOutFallback = setupWithSingleFile(testData, manager);

    sConf.set(PropertyKey.USER_CLIENT_CACHE_FALLBACK_ENABLED, true);
    LocalCacheFileInStream streamWithFallback = setupWithSingleFile(testData, manager);
    Assert.assertEquals(100, streamWithFallback.positionedRead(0, new byte[10], 100, 100));
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_POSITION_READ_FALLBACK.getName()).getCount());
  }

  @Test
  public void testPositionReadFallBack() throws Exception
  {
    int pages = 10;
    int fileSize = mPageSize * pages;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    sConf.set(PropertyKey.USER_CLIENT_CACHE_FALLBACK_ENABLED, false);
    //by default local cache fallback is not enabled, the read should fail for any error
    LocalCacheFileInStream streamWithOutFallback = setupWithSingleFile(testData, manager);
    try {
      streamWithOutFallback.positionedRead(0, new byte[10], 100, 100);
      fail("Expect position read fail here.");
    } catch (ArrayIndexOutOfBoundsException e) {
      //expected exception
    }
    sConf.set(PropertyKey.USER_CLIENT_CACHE_FALLBACK_ENABLED, true);
    LocalCacheFileInStream streamWithFallback = setupWithSingleFile(testData, manager);
    Assert.assertEquals(100, streamWithFallback.positionedRead(0, new byte[10], 100, 100));
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_POSITION_READ_FALLBACK.getName()).getCount());
  }

  @Test
  public void testPositionReadWithPerThreadContextSameThread() throws Exception
  {
    int fileSize = mPageSize * 5;
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    // read last page with cache enabled (cache miss)
    CachePerThreadContext.get().setCacheEnabled(true);
    stream.positionedRead(fileSize - mPageSize, new byte[mPageSize], 0, mPageSize);
    Assert.assertEquals(0,
        MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_READ_CACHE.getName()).getCount());
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_EXTERNAL_REQUESTS.getName()).getCount());
    Assert.assertEquals(0,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_HIT_REQUESTS.getName()).getCount());
    // read last page with cache enabled (cache hit)
    stream.positionedRead(fileSize - mPageSize, new byte[mPageSize], 0, mPageSize);
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_EXTERNAL_REQUESTS.getName()).getCount());
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_HIT_REQUESTS.getName()).getCount());

    MetricsSystem.clearAllMetrics();
    // read first page with cache disabled (cache miss)
    CachePerThreadContext.get().setCacheEnabled(false);
    stream.positionedRead(0, new byte[mPageSize], 0, mPageSize);
    Assert.assertEquals(0,
        MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_READ_CACHE.getName()).getCount());
    Assert.assertEquals(1,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_EXTERNAL_REQUESTS.getName()).getCount());
    Assert.assertEquals(0,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_HIT_REQUESTS.getName()).getCount());
    // read last page again with cache enabled (cache miss)
    stream.positionedRead(0, new byte[mPageSize], 0, mPageSize);
    Assert.assertEquals(2,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_EXTERNAL_REQUESTS.getName()).getCount());
    Assert.assertEquals(0,
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_HIT_REQUESTS.getName()).getCount());
  }

  private LocalCacheFileInStream setupWithSingleFile(byte[] data, CacheManager manager)
      throws Exception {
    Map<AlluxioURI, byte[]> files = new HashMap<>();
    AlluxioURI testFilename = new AlluxioURI("/test");
    files.put(testFilename, data);

    ByteArrayFileSystem fs = new ByteArrayFileSystem(files);

    return new LocalCacheFileInStream(fs.getStatus(testFilename),
        (status) -> fs.openFile(status, OpenFilePOptions.getDefaultInstance()), manager, sConf,
        Optional.empty());
  }

  private Map<AlluxioURI, LocalCacheFileInStream> setupWithMultipleFiles(Map<String, byte[]> files,
      CacheManager manager) {
    Map<AlluxioURI, byte[]> fileMap = files.entrySet().stream()
        .collect(Collectors.toMap(entry -> new AlluxioURI(entry.getKey()), Map.Entry::getValue));
    final ByteArrayFileSystem fs = new ByteArrayFileSystem(fileMap);

    Map<AlluxioURI, LocalCacheFileInStream> ret = new HashMap<>();
    fileMap.entrySet().forEach(entry -> {
      try {
        ret.put(entry.getKey(),
            new LocalCacheFileInStream(fs.getStatus(entry.getKey()),
                (status) -> fs.openFile(status, OpenFilePOptions.getDefaultInstance()), manager,
                sConf, Optional.empty()));
      } catch (Exception e) {
        // skip
      }
    });
    return ret;
  }

  private URIStatus generateURIStatus(String path, long len) {
    FileInfo info = new FileInfo();
    info.setFileId(path.hashCode());
    info.setPath(path);
    info.setUfsPath(path);
    info.setLength(len);
    return new URIStatus(info);
  }

  private void verifyReadFullFile(int fileSize, int bufferSize, int pages) throws Exception {
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    // cache miss
    byte[] cacheMiss = new byte[bufferSize];
    Assert.assertEquals(fileSize, stream.read(cacheMiss));
    Assert.assertArrayEquals(testData, Arrays.copyOfRange(cacheMiss, 0, fileSize));
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(pages, manager.mPagesCached);

    // cache hit
    stream.seek(0);
    byte[] cacheHit = new byte[bufferSize];
    Assert.assertEquals(fileSize, stream.read(cacheHit));
    Assert.assertArrayEquals(testData, Arrays.copyOfRange(cacheHit, 0, fileSize));
    Assert.assertEquals(pages, manager.mPagesServed);
  }

  private void verifyReadFullFileThroughReadByteBufferMethod(int fileSize, int bufferSize,
      int pages) throws Exception {
    byte[] testData = BufferUtils.getIncreasingByteArray(fileSize);
    ByteArrayCacheManager manager = new ByteArrayCacheManager();
    LocalCacheFileInStream stream = setupWithSingleFile(testData, manager);

    // cache miss
    byte[] cacheMiss = new byte[bufferSize];
    ByteBuffer cacheMissBuffer = ByteBuffer.wrap(cacheMiss);
    Assert.assertEquals(fileSize, stream.read(cacheMissBuffer));
    Assert.assertArrayEquals(testData, Arrays.copyOfRange(cacheMiss, 0, fileSize));
    Assert.assertEquals(0, manager.mPagesServed);
    Assert.assertEquals(pages, manager.mPagesCached);

    // cache hit
    stream.seek(0);
    byte[] cacheHit = new byte[bufferSize];
    ByteBuffer cacheHitBuffer = ByteBuffer.wrap(cacheHit);
    Assert.assertEquals(fileSize, stream.read(cacheHitBuffer));
    Assert.assertArrayEquals(testData, Arrays.copyOfRange(cacheHit, 0, fileSize));
    Assert.assertEquals(pages, manager.mPagesServed);
  }

  /**
   * Implementation of cache manager that stores cached data in byte arrays in memory.
   */
  private class ByteArrayCacheManager implements CacheManager {
    private final Map<PageId, byte[]> mPages;

    /** Metrics for test validation. */
    long mPagesServed = 0;
    long mPagesCached = 0;

    ByteArrayCacheManager() {
      mPages = new HashMap<>();
    }

    @Override
    public boolean put(PageId pageId, ByteBuffer page, CacheContext cacheContext) {
      mPages.put(pageId, page.array());
      mPagesCached++;
      return true;
    }

    @Override
    public int get(PageId pageId, int pageOffset, int bytesToRead, ReadTargetBuffer target,
        CacheContext cacheContext) {
      if (!mPages.containsKey(pageId)) {
        return 0;
      }
      mPagesServed++;
      target.writeBytes(mPages.get(pageId), pageOffset, bytesToRead);
      return bytesToRead;
    }

    @Override
    public void commitFile(String fileId) {
      throw new UnsupportedOperationException("commitFile method is unsupported. ");
    }

    @Override
    public int getAndLoad(PageId pageId, int pageOffset, int bytesToRead,
        ReadTargetBuffer buffer, CacheContext cacheContext,
        Supplier<byte[]> externalDataSupplier) {
      int bytesRead = get(pageId, pageOffset,
          bytesToRead, buffer, cacheContext);
      if (bytesRead > 0) {
        return bytesRead;
      }
      byte[] page = externalDataSupplier.get();
      if (page.length == 0) {
        return 0;
      }
      buffer.writeBytes(page, pageOffset, bytesToRead);
      put(pageId, page, cacheContext);
      return bytesToRead;
    }

    @Override
    public boolean delete(PageId pageId) {
      return mPages.remove(pageId) != null;
    }

    @Override
    public State state() {
      return State.READ_WRITE;
    }

    @Override
    public boolean append(PageId pageId, int appendAt, byte[] page, CacheContext cacheContext) {
      return false;
    }

    @Override
    public void close() throws Exception {
      // no-op
    }

    @Override
    public void deleteFile(String fileId) {
      // no-op
    }

    @Override
    public void deleteTempFile(String fileId) {
      // no-op
    }

    @Override
    public Optional<CacheUsage> getUsage() {
      return Optional.of(new Usage());
    }

    @Override
    public Optional<DataFileChannel> getDataFileChannel(PageId pageId, int pageOffset,
        int bytesToRead, CacheContext cacheContext) {
      return Optional.empty();
    }

    class Usage implements CacheUsage {
      @Override
      public Optional<CacheUsage> partitionedBy(PartitionDescriptor<?> partition) {
        return Optional.empty();
      }

      @Override
      public long used() {
        return mPages.values().stream().mapToInt(page -> page.length).sum();
      }

      @Override
      public long available() {
        return Integer.MAX_VALUE;
      }

      @Override
      public long capacity() {
        return Integer.MAX_VALUE;
      }
    }
  }

  /**
   * An implementation of file system which is a store of filenames to byte arrays. Only
   * {@link FileSystem#openFile(AlluxioURI)} and its variants are supported.
   */
  private class ByteArrayFileSystem implements FileSystem {
    private final Map<AlluxioURI, byte[]> mFiles;

    ByteArrayFileSystem(Map<AlluxioURI, byte[]> files) {
      mFiles = files;
    }

    @Override
    public boolean isClosed() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void checkAccess(AlluxioURI path, CheckAccessPOptions options)
        throws InvalidPathException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void createDirectory(AlluxioURI path, CreateDirectoryPOptions options)
        throws FileAlreadyExistsException, InvalidPathException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileOutStream createFile(AlluxioURI path, CreateFilePOptions options)
        throws FileAlreadyExistsException, InvalidPathException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(AlluxioURI path, DeletePOptions options)
        throws DirectoryNotEmptyException, FileDoesNotExistException, IOException,
        AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean exists(AlluxioURI path, ExistsPOptions options)
        throws InvalidPathException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void free(AlluxioURI path, FreePOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<BlockLocationInfo> getBlockLocations(AlluxioURI path)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<BlockLocationInfo> getBlockLocations(URIStatus status)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public AlluxioConfiguration getConf() {
      return sConf;
    }

    @Override
    public URIStatus getStatus(AlluxioURI path, GetStatusPOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      if (mFiles.containsKey(path)) {
        return generateURIStatus(path.getPath(), mFiles.get(path).length);
      } else {
        throw new FileDoesNotExistException(path);
      }
    }

    @Override
    public void iterateStatus(AlluxioURI path, ListStatusPOptions options,
        Consumer<? super URIStatus> action)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<URIStatus> listStatus(AlluxioURI path, ListStatusPOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListStatusPartialResult listStatusPartial(
        AlluxioURI path, ListStatusPartialPOptions options) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void loadMetadata(AlluxioURI path, ListStatusPOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void mount(AlluxioURI alluxioPath, AlluxioURI ufsPath, MountPOptions options)
        throws IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateMount(AlluxioURI alluxioPath, MountPOptions options)
        throws IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, MountPointInfo> getMountTable(boolean checkUfs)
        throws IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SyncPointInfo> getSyncPathList() throws IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileInStream openFile(AlluxioURI path, OpenFilePOptions options)
        throws FileDoesNotExistException, OpenDirectoryException, FileIncompleteException,
        IOException, AlluxioException {
      if (mFiles.containsKey(path)) {
        return new MockFileInStream(mFiles.get(path));
      } else {
        throw new FileDoesNotExistException(path);
      }
    }

    @Override
    public FileInStream openFile(URIStatus status, OpenFilePOptions options)
        throws FileDoesNotExistException, OpenDirectoryException, FileIncompleteException,
        IOException, AlluxioException {
      AlluxioURI path = new AlluxioURI(status.getPath());
      if (mFiles.containsKey(path)) {
        return new MockFileInStream(mFiles.get(path));
      } else {
        throw new FileDoesNotExistException(path);
      }
    }

    @Override
    public void persist(AlluxioURI path, ScheduleAsyncPersistencePOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void rename(AlluxioURI src, AlluxioURI dst, RenamePOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public AlluxioURI reverseResolve(AlluxioURI ufsUri)
        throws IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setAcl(AlluxioURI path, SetAclAction action, List<AclEntry> entries,
        SetAclPOptions options) throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void startSync(AlluxioURI path)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void stopSync(AlluxioURI path)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setAttribute(AlluxioURI path, SetAttributePOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void unmount(AlluxioURI path, UnmountPOptions options)
        throws IOException, AlluxioException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void needsSync(AlluxioURI path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<String> submitJob(JobRequest jobRequest) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean stopJob(JobDescription jobDescription) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getJobProgress(JobDescription jobDescription,
        JobProgressReportFormat format, boolean verbose) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SyncMetadataPResponse syncMetadata(AlluxioURI path, SyncMetadataPOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      return null;
    }

    @Override
    public SyncMetadataAsyncPResponse syncMetadataAsync(AlluxioURI path,
                                                        SyncMetadataPOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      return null;
    }

    @Override
    public GetSyncProgressPResponse getSyncProgress(long taskGroupId)
        throws FileDoesNotExistException, IOException, AlluxioException {
      return null;
    }

    @Override
    public CancelSyncMetadataPResponse cancelSyncMetadata(long taskGroupId)
        throws IOException, AlluxioException {
      return null;
    }

    @Override
    public void close() throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  private static class StepTicker extends Ticker {
    private long mNow = 0;
    private final Map<Type, Long> mTimeMap = Maps.newEnumMap(Type.class);

    enum Type {
      CACHE_HIT, CACHE_MISS;
    }

    public long getNow() {
      return mNow;
    }

    public long get(Type type) {
      return mTimeMap.getOrDefault(type, 0L);
    }

    public void advance(Type type) {
      mNow += 1;
      mTimeMap.compute(type, (k, v) -> v == null ? 1 : v + 1);
    }

    @Override
    public long read() {
      return mNow;
    }
  }

  private class MockedCacheContext extends CacheContext {

    private final BiConsumer<String, Long> mCounter;

    public MockedCacheContext(BiConsumer<String, Long> counter) {
      mCounter = counter;
    }

    @Override
    public void incrementCounter(String name, StatsUnit unit, long value) {
      mCounter.accept(name, value);
    }
  }

  private class TimedByteArrayFileSystem extends ByteArrayFileSystem {
    private final StepTicker mTicker;
    private final BiConsumer<String, Long> mCounter;

    TimedByteArrayFileSystem(Map<AlluxioURI, byte[]> files,
        BiConsumer<String, Long> counter, StepTicker ticker) {
      super(files);
      mTicker = ticker;
      mCounter = counter;
    }

    @Override
    public URIStatus getStatus(AlluxioURI path, GetStatusPOptions options)
        throws FileDoesNotExistException, IOException, AlluxioException {
      URIStatus status = super.getStatus(path, options);
      URIStatus withCacheContextStatus = new URIStatus(status.getFileInfo(),
          new MockedCacheContext(mCounter));
      return withCacheContextStatus;
    }

    @Override
    public FileInStream openFile(AlluxioURI path, OpenFilePOptions options)
        throws FileDoesNotExistException, OpenDirectoryException, FileIncompleteException,
        IOException, AlluxioException {
      FileInStream delegateToStream = super.openFile(path, options);
      return new TimedMockFileInStream(delegateToStream, mTicker);
    }

    @Override
    public FileInStream openFile(URIStatus status, OpenFilePOptions options)
        throws FileDoesNotExistException, OpenDirectoryException, FileIncompleteException,
        IOException, AlluxioException {
      FileInStream delegateToStream = super.openFile(status, options);
      return new TimedMockFileInStream(delegateToStream, mTicker);
    }
  }

  private class TimedMockByteArrayCacheManager extends ByteArrayCacheManager {
    private final StepTicker mTicker;

    public TimedMockByteArrayCacheManager(StepTicker ticker) {
      mTicker = ticker;
    }

    @Override
    public int get(PageId pageId, int pageOffset, int bytesToRead, ReadTargetBuffer target,
        CacheContext cacheContext) {
      int read = super.get(pageId, pageOffset, bytesToRead, target, cacheContext);
      if (read > 0) {
        mTicker.advance(StepTicker.Type.CACHE_HIT);
      }
      // when cache misses, time elapsed during `get` should not count towards external read metric
      return read;
    }
  }

  private static class TimedMockFileInStream extends FileInStream {
    private final FileInStream mDelegate;
    private final StepTicker mTicker;

    public TimedMockFileInStream(FileInStream delegate, StepTicker ticker) {
      mDelegate = delegate;
      mTicker = ticker;
    }

    @Override
    public int read(ByteBuffer byteBuffer, int off, int len) throws IOException {
      mTicker.advance(StepTicker.Type.CACHE_MISS);
      return mDelegate.read(byteBuffer, off, len);
    }

    @Override
    public long getPos() throws IOException {
      return mDelegate.getPos();
    }

    @Override
    public long remaining() {
      return mDelegate.remaining();
    }

    @Override
    public void seek(long pos) throws IOException {
      mDelegate.seek(pos);
    }

    @Override
    public int positionedRead(long position, byte[] buffer, int offset, int length)
        throws IOException {
      mTicker.advance(StepTicker.Type.CACHE_MISS);
      return mDelegate.positionedRead(position, buffer, offset, length);
    }
  }

  private class MultiReadByteArrayFileSystem extends ByteArrayFileSystem {
    MultiReadByteArrayFileSystem(Map<AlluxioURI, byte[]> files) {
      super(files);
    }

    @Override
    public FileInStream openFile(AlluxioURI path, OpenFilePOptions options)
        throws FileDoesNotExistException, OpenDirectoryException, FileIncompleteException,
        IOException, AlluxioException {
      return new MultiReadFileInStream(super.openFile(path, options));
    }

    @Override
    public FileInStream openFile(URIStatus status, OpenFilePOptions options)
        throws FileDoesNotExistException, OpenDirectoryException, FileIncompleteException,
        IOException, AlluxioException {
      return new MultiReadFileInStream(super.openFile(status, options));
    }
  }

  /**
   * Mock implementation of {@link FileInStream} which delegates to a {@link ByteArrayInputStream}.
   * This implementation may not serve the full read in a single call.
   */
  private class MultiReadFileInStream extends FileInStream {
    private final FileInStream mIn;

    /**
     * Creates an FileInStream that may not serve read calls in a single call.
     *
     * @param in the backing FileInStream
     */
    public MultiReadFileInStream(FileInStream in) {
      mIn = in;
    }

    @Override
    public int read() throws IOException {
      return mIn.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int toRead = len > 1 ? ThreadLocalRandom.current().nextInt(1, len) : len;
      return mIn.read(b, off, toRead);
    }

    @Override
    public int read(ByteBuffer buf) throws IOException {
      return mIn.read(buf);
    }

    @Override
    public long getPos() throws IOException {
      return mIn.getPos();
    }

    @Override
    public long remaining() {
      return mIn.remaining();
    }

    @Override
    public void seek(long pos) throws IOException {
      mIn.seek(pos);
    }

    @Override
    public int positionedRead(long position, byte[] buffer, int offset, int length)
        throws IOException {
      return mIn.positionedRead(position, buffer, offset, length);
    }
  }
}
