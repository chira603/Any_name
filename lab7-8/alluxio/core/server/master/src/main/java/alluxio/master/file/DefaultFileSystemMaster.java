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

package alluxio.master.file;

import static alluxio.master.file.InodeSyncStream.SyncStatus.FAILED;
import static alluxio.master.file.InodeSyncStream.SyncStatus.NOT_NEEDED;
import static alluxio.master.file.InodeSyncStream.SyncStatus.OK;
import static alluxio.metrics.MetricInfo.UFS_OP_SAVED_PREFIX;

import alluxio.AlluxioURI;
import alluxio.ClientContext;
import alluxio.Constants;
import alluxio.Server;
import alluxio.client.file.FileSystemContext;
import alluxio.client.job.JobMasterClient;
import alluxio.client.job.JobMasterClientPool;
import alluxio.clock.SystemClock;
import alluxio.collections.Pair;
import alluxio.collections.PrefixList;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AccessControlException;
import alluxio.exception.AlluxioException;
import alluxio.exception.BlockInfoException;
import alluxio.exception.ConnectionFailedException;
import alluxio.exception.DirectoryNotEmptyException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.FileAlreadyCompletedException;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidFileSizeException;
import alluxio.exception.InvalidPathException;
import alluxio.exception.UnexpectedAlluxioException;
import alluxio.exception.runtime.NotFoundRuntimeException;
import alluxio.exception.status.FailedPreconditionException;
import alluxio.exception.status.InvalidArgumentException;
import alluxio.exception.status.NotFoundException;
import alluxio.exception.status.PermissionDeniedException;
import alluxio.exception.status.ResourceExhaustedException;
import alluxio.exception.status.UnavailableException;
import alluxio.file.options.DescendantType;
import alluxio.grpc.CancelSyncMetadataPResponse;
import alluxio.grpc.DeletePOptions;
import alluxio.grpc.FileSystemMasterCommonPOptions;
import alluxio.grpc.GetStatusPOptions;
import alluxio.grpc.GetSyncProgressPResponse;
import alluxio.grpc.GrpcService;
import alluxio.grpc.GrpcUtils;
import alluxio.grpc.LoadDescendantPType;
import alluxio.grpc.LoadMetadataPOptions;
import alluxio.grpc.LoadMetadataPType;
import alluxio.grpc.MountPOptions;
import alluxio.grpc.ServiceType;
import alluxio.grpc.SetAclAction;
import alluxio.grpc.SetAttributePOptions;
import alluxio.grpc.SyncMetadataAsyncPResponse;
import alluxio.grpc.SyncMetadataPResponse;
import alluxio.grpc.TtlAction;
import alluxio.heartbeat.FixedIntervalSupplier;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.HeartbeatThread;
import alluxio.job.plan.persist.PersistConfig;
import alluxio.job.wire.JobInfo;
import alluxio.master.CoreMaster;
import alluxio.master.CoreMasterContext;
import alluxio.master.ProtobufUtils;
import alluxio.master.audit.AsyncUserAccessAuditLogWriter;
import alluxio.master.audit.AuditContext;
import alluxio.master.block.BlockId;
import alluxio.master.block.BlockMaster;
import alluxio.master.file.activesync.ActiveSyncManager;
import alluxio.master.file.contexts.CallTracker;
import alluxio.master.file.contexts.CheckAccessContext;
import alluxio.master.file.contexts.CheckConsistencyContext;
import alluxio.master.file.contexts.CompleteFileContext;
import alluxio.master.file.contexts.CreateDirectoryContext;
import alluxio.master.file.contexts.CreateFileContext;
import alluxio.master.file.contexts.DeleteContext;
import alluxio.master.file.contexts.ExistsContext;
import alluxio.master.file.contexts.FreeContext;
import alluxio.master.file.contexts.GetStatusContext;
import alluxio.master.file.contexts.InternalOperationContext;
import alluxio.master.file.contexts.ListStatusContext;
import alluxio.master.file.contexts.LoadMetadataContext;
import alluxio.master.file.contexts.MountContext;
import alluxio.master.file.contexts.OperationContext;
import alluxio.master.file.contexts.RenameContext;
import alluxio.master.file.contexts.ScheduleAsyncPersistenceContext;
import alluxio.master.file.contexts.SetAclContext;
import alluxio.master.file.contexts.SetAttributeContext;
import alluxio.master.file.contexts.SyncMetadataContext;
import alluxio.master.file.contexts.WorkerHeartbeatContext;
import alluxio.master.file.mdsync.DefaultSyncProcess;
import alluxio.master.file.mdsync.TaskGroup;
import alluxio.master.file.meta.FileSystemMasterView;
import alluxio.master.file.meta.Inode;
import alluxio.master.file.meta.InodeDirectory;
import alluxio.master.file.meta.InodeDirectoryIdGenerator;
import alluxio.master.file.meta.InodeDirectoryView;
import alluxio.master.file.meta.InodeFile;
import alluxio.master.file.meta.InodeLockManager;
import alluxio.master.file.meta.InodePathPair;
import alluxio.master.file.meta.InodeTree;
import alluxio.master.file.meta.InodeTree.LockPattern;
import alluxio.master.file.meta.LockedInodePath;
import alluxio.master.file.meta.LockedInodePathList;
import alluxio.master.file.meta.LockingScheme;
import alluxio.master.file.meta.MountTable;
import alluxio.master.file.meta.PersistenceState;
import alluxio.master.file.meta.UfsAbsentPathCache;
import alluxio.master.file.meta.UfsBlockLocationCache;
import alluxio.master.file.meta.UfsSyncPathCache;
import alluxio.master.file.meta.options.MountInfo;
import alluxio.master.journal.DelegatingJournaled;
import alluxio.master.journal.FileSystemMergeJournalContext;
import alluxio.master.journal.JournalContext;
import alluxio.master.journal.Journaled;
import alluxio.master.journal.JournaledGroup;
import alluxio.master.journal.NoopJournalContext;
import alluxio.master.journal.checkpoint.CheckpointName;
import alluxio.master.journal.ufs.UfsJournalSystem;
import alluxio.master.metastore.DelegatingReadOnlyInodeStore;
import alluxio.master.metastore.InodeStore;
import alluxio.master.metastore.ReadOnlyInodeStore;
import alluxio.master.metrics.TimeSeriesStore;
import alluxio.master.scheduler.DefaultWorkerProvider;
import alluxio.master.scheduler.JournaledJobMetaStore;
import alluxio.master.scheduler.Scheduler;
import alluxio.metrics.Metric;
import alluxio.metrics.MetricInfo;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;
import alluxio.metrics.TimeSeries;
import alluxio.proto.journal.File;
import alluxio.proto.journal.File.NewBlockEntry;
import alluxio.proto.journal.File.RenameEntry;
import alluxio.proto.journal.File.SetAclEntry;
import alluxio.proto.journal.File.UpdateInodeEntry;
import alluxio.proto.journal.File.UpdateInodeFileEntry;
import alluxio.proto.journal.File.UpdateInodeFileEntry.Builder;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.recorder.Recorder;
import alluxio.resource.CloseableIterator;
import alluxio.resource.CloseableResource;
import alluxio.resource.LockResource;
import alluxio.retry.CountingRetry;
import alluxio.retry.RetryPolicy;
import alluxio.security.authentication.AuthType;
import alluxio.security.authentication.AuthenticatedClientUser;
import alluxio.security.authentication.ClientContextServerInjector;
import alluxio.security.authorization.AclEntry;
import alluxio.security.authorization.AclEntryType;
import alluxio.security.authorization.Mode;
import alluxio.underfs.Fingerprint;
import alluxio.underfs.MasterUfsManager;
import alluxio.underfs.UfsFileStatus;
import alluxio.underfs.UfsManager;
import alluxio.underfs.UfsMode;
import alluxio.underfs.UfsStatus;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.UnderFileSystemConfiguration;
import alluxio.underfs.options.MkdirsOptions;
import alluxio.util.CommonUtils;
import alluxio.util.IdUtils;
import alluxio.util.LogUtils;
import alluxio.util.ModeUtils;
import alluxio.util.SecurityUtils;
import alluxio.util.ThreadFactoryUtils;
import alluxio.util.UnderFileSystemUtils;
import alluxio.util.executor.ExecutorServiceFactories;
import alluxio.util.executor.ExecutorServiceFactory;
import alluxio.util.io.PathUtils;
import alluxio.util.proto.ProtoUtils;
import alluxio.wire.BlockInfo;
import alluxio.wire.BlockLocation;
import alluxio.wire.CommandType;
import alluxio.wire.FileBlockInfo;
import alluxio.wire.FileInfo;
import alluxio.wire.FileSystemCommand;
import alluxio.wire.FileSystemCommandOptions;
import alluxio.wire.MountPointInfo;
import alluxio.wire.PersistCommandOptions;
import alluxio.wire.PersistFile;
import alluxio.wire.SyncPointInfo;
import alluxio.wire.UfsInfo;
import alluxio.wire.WorkerInfo;
import alluxio.worker.job.JobMasterClientContext;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.protobuf.ByteString;
import io.grpc.ServerInterceptors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.Spliterators;
import java.util.Stack;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * The master that handles all file system metadata management.
 */
@NotThreadSafe // TODO(jiri): make thread-safe (c.f. ALLUXIO-1664)
public class DefaultFileSystemMaster extends CoreMaster
    implements FileSystemMaster, DelegatingJournaled {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultFileSystemMaster.class);
  private static final Set<Class<? extends Server>> DEPS = ImmutableSet.of(BlockMaster.class);

  /** The number of threads to use in the {@link #mPersistCheckerPool}. */
  private static final int PERSIST_CHECKER_POOL_THREADS = 128;

  /**
   * Locking in DefaultFileSystemMaster
   *
   * Individual paths are locked in the inode tree. In order to read or write any inode, the path
   * must be locked. A path is locked via one of the lock methods in {@link InodeTree}, such as
   * {@link InodeTree#lockInodePath(AlluxioURI, LockMode)} or
   * {@link InodeTree#lockFullInodePath(AlluxioURI, LockMode)}. These lock methods return
   * an {@link LockedInodePath}, which represents a locked path of inodes. These locked paths
   * ({@link LockedInodePath}) must be unlocked. In order to ensure a locked
   * {@link LockedInodePath} is always unlocked, the following paradigm is recommended:
   *
   * <p><blockquote><pre>
   *    try (LockedInodePath inodePath = mInodeTree.lockInodePath(path, LockPattern.READ)) {
   *      ...
   *    }
   * </pre></blockquote>
   *
   * When locking a path in the inode tree, it is possible that other concurrent operations have
   * modified the inode tree while a thread is waiting to acquire a lock on the inode. Lock
   * acquisitions throw {@link InvalidPathException} to indicate that the inode structure is no
   * longer consistent with what the caller original expected, for example if the inode
   * previously obtained at /pathA has been renamed to /pathB during the wait for the inode lock.
   * Methods which specifically act on a path will propagate this exception to the caller, while
   * methods which iterate over child nodes can safely ignore the exception and treat the inode
   * as no longer a child.
   *
   * JournalContext, BlockDeletionContext, and RpcContext
   *
   * RpcContext is an aggregator for various contexts which get passed around through file system
   * master methods.
   *
   * Currently there are two types of contexts that get passed around: {@link JournalContext} and
   * {@link BlockDeletionContext}. These contexts are used to register work that should be done when
   * the context closes. The journal context tracks journal entries which need to be flushed, while
   * the block deletion context tracks which blocks need to be deleted in the {@link BlockMaster}.
   *
   * File system master journal entries should be written before blocks are deleted in the block
   * master, so journal context should always be closed before block deletion context. In order to
   * ensure that contexts are closed and closed in the right order, the following paradign is
   * recommended:
   *
   * <p><blockquote><pre>
   *    try (RpcContext rpcContext = createRpcContext()) {
   *      // access journal context with rpcContext.getJournalContext()
   *      // access block deletion context with rpcContext.getBlockDeletionContext()
   *      ...
   *    }
   * </pre></blockquote>
   *
   * When used in conjunction with {@link LockedInodePath} and {@link AuditContext}, the usage
   * should look like
   *
   * <p><blockquote><pre>
   *    try (RpcContext rpcContext = createRpcContext();
   *         LockedInodePath inodePath = mInodeTree.lockInodePath(...);
   *         FileSystemMasterAuditContext auditContext = createAuditContext(...)) {
   *      ...
   *    }
   * </pre></blockquote>
   *
   * NOTE: Because resources are released in the opposite order they are acquired, the
   * {@link JournalContext}, {@link BlockDeletionContext}, or {@link RpcContext} resources should be
   * always created before any {@link LockedInodePath} resources to avoid holding an inode path lock
   * while waiting for journal IO.
   *
   * User access audit logging in the FileSystemMaster
   *
   * User accesses to file system metadata should be audited. The intent to write audit log and the
   * actual writing of the audit log is decoupled so that operations are not holding metadata locks
   * waiting on the audit log IO. In particular {@link AsyncUserAccessAuditLogWriter} uses a
   * separate thread to perform actual audit log IO. In order for audit log entries to preserve
   * the order of file system operations, the intention of auditing should be submitted to
   * {@link AsyncUserAccessAuditLogWriter} while holding locks on the inode path. That said, the
   * {@link AuditContext} resources should always live within the scope of {@link LockedInodePath},
   * i.e. created after {@link LockedInodePath}. Otherwise, the order of audit log entries may not
   * reflect the actual order of the user accesses.
   * Resources are released in the opposite order they are acquired, the
   * {@link AuditContext#close()} method is called before {@link LockedInodePath#close()}, thus
   * guaranteeing the order.
   *
   * Method Conventions in the FileSystemMaster
   *
   * All of the flow of the FileSystemMaster follow a convention. There are essentially 4 main
   * types of methods:
   *   (A) public api methods
   *   (B) private (or package private) internal methods
   *
   * (A) public api methods:
   * These methods are public and are accessed by the RPC and REST APIs. These methods lock all
   * the required paths, and also perform all permission checking.
   * (A) cannot call (A)
   * (A) can call (B)
   *
   * (B) private (or package private) internal methods:
   * These methods perform the rest of the work. The names of these
   * methods are suffixed by "Internal". These are typically called by the (A) methods.
   * (B) cannot call (A)
   * (B) can call (B)
   */

  /** Handle to the block master. */
  private final BlockMaster mBlockMaster;

  /** This manages the file system inode structure. This must be journaled. */
  protected final InodeTree mInodeTree;

  /** Store for holding inodes. */
  private final ReadOnlyInodeStore mInodeStore;

  /** This manages inode locking. */
  private final InodeLockManager mInodeLockManager;

  /** This manages the file system mount points. */
  private final MountTable mMountTable;

  /** This generates unique directory ids. This must be journaled. */
  private final InodeDirectoryIdGenerator mDirectoryIdGenerator;

  /** This checks user permissions on different operations. */
  private final PermissionChecker mPermissionChecker;

  /** List of paths to always keep in memory. */
  private final PrefixList mWhitelist;

  /** A pool of job master clients. */
  private final JobMasterClientPool mJobMasterClientPool;

  /** Set of file IDs to persist. */
  private final Map<Long, alluxio.time.ExponentialTimer> mPersistRequests;

  /** Map from file IDs to persist jobs. */
  private final Map<Long, PersistJob> mPersistJobs;

  /** The manager of all ufs. */
  private final MasterUfsManager mUfsManager;

  /** This caches absent paths in the UFS. */
  private final UfsAbsentPathCache mUfsAbsentPathCache;

  /** This caches block locations in the UFS. */
  private final UfsBlockLocationCache mUfsBlockLocationCache;

  /** The {@link JournaledGroup} representing all the subcomponents which require journaling. */
  private final JournaledGroup mJournaledGroup;

  /** List of strings which are blacklisted from async persist. */
  private final List<String> mPersistBlacklist;

  /** Thread pool which asynchronously handles the completion of persist jobs. */
  private java.util.concurrent.ThreadPoolExecutor mPersistCheckerPool;

  private final ActiveSyncManager mSyncManager;

  /** Log writer for user access audit log. */
  protected volatile AsyncUserAccessAuditLogWriter mAsyncAuditLogWriter;

  /** Stores the time series for various metrics which are exposed in the UI. */
  private final TimeSeriesStore mTimeSeriesStore;

  @Nullable private final AccessTimeUpdater mAccessTimeUpdater;

  /** Used to check pending/running backup from RPCs. */
  protected final CallTracker mStateLockCallTracker;
  private final Scheduler mScheduler;

  final Clock mClock;

  /** Used to determine if we should journal inode journals within a JournalContext. */
  private final boolean mMergeInodeJournals = Configuration.getBoolean(
      PropertyKey.MASTER_FILE_SYSTEM_MERGE_INODE_JOURNALS
  );

  public final int mRecursiveOperationForceFlushEntries = Configuration
      .getInt(PropertyKey.MASTER_RECURSIVE_OPERATION_JOURNAL_FORCE_FLUSH_MAX_ENTRIES);
  private final ThreadPoolExecutor mSyncPrefetchExecutor = new ThreadPoolExecutor(
      Configuration.getInt(PropertyKey.MASTER_METADATA_SYNC_UFS_PREFETCH_POOL_SIZE),
      Configuration.getInt(PropertyKey.MASTER_METADATA_SYNC_UFS_PREFETCH_POOL_SIZE),
      1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(),
      ThreadFactoryUtils.build("alluxio-ufs-sync-prefetch-%d", false));
  final ExecutorService mSyncPrefetchExecutorIns =
      Configuration.getBoolean(PropertyKey.MASTER_METADATA_SYNC_INSTRUMENT_EXECUTOR)
          ? MetricsSystem.executorService(mSyncPrefetchExecutor,
          MetricKey.MASTER_METADATA_SYNC_PREFETCH_EXECUTOR.getName()) : mSyncPrefetchExecutor;

  private final ThreadPoolExecutor mSyncMetadataExecutor = new ThreadPoolExecutor(
      Configuration.getInt(PropertyKey.MASTER_METADATA_SYNC_EXECUTOR_POOL_SIZE),
      Configuration.getInt(PropertyKey.MASTER_METADATA_SYNC_EXECUTOR_POOL_SIZE),
      1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(),
      ThreadFactoryUtils.build("alluxio-ufs-sync-%d", false));
  final ExecutorService mSyncMetadataExecutorIns =
      Configuration.getBoolean(PropertyKey.MASTER_METADATA_SYNC_INSTRUMENT_EXECUTOR)
          ? MetricsSystem.executorService(mSyncMetadataExecutor,
          MetricKey.MASTER_METADATA_SYNC_EXECUTOR.getName()) : mSyncMetadataExecutor;

  final ThreadPoolExecutor mActiveSyncMetadataExecutor = new ThreadPoolExecutor(
      Configuration.getInt(PropertyKey.MASTER_METADATA_SYNC_EXECUTOR_POOL_SIZE),
      Configuration.getInt(PropertyKey.MASTER_METADATA_SYNC_EXECUTOR_POOL_SIZE),
      1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(),
      ThreadFactoryUtils.build("alluxio-ufs-active-sync-%d", false));
  private HeartbeatThread mReplicationCheckHeartbeatThread;

  private final DefaultSyncProcess mDefaultSyncProcess;

  /**
   * Creates a new instance of {@link DefaultFileSystemMaster}.
   *
   * @param blockMaster a block master handle
   * @param masterContext the context for Alluxio master
   */
  public DefaultFileSystemMaster(BlockMaster blockMaster, CoreMasterContext masterContext) {
    this(blockMaster, masterContext, ExecutorServiceFactories.cachedThreadPool(
        Constants.FILE_SYSTEM_MASTER_NAME), Clock.systemUTC());
  }

  /**
   * Creates a new instance of {@link DefaultFileSystemMaster}.
   *
   * @param blockMaster a block master handle
   * @param masterContext the context for Alluxio master
   * @param executorServiceFactory a factory for creating the executor service to use for running
   *        maintenance threads
   * @param clock the clock used to compute the current time
   */
  public DefaultFileSystemMaster(BlockMaster blockMaster, CoreMasterContext masterContext,
      ExecutorServiceFactory executorServiceFactory, Clock clock) {
    super(masterContext, new SystemClock(), executorServiceFactory);

    mBlockMaster = blockMaster;
    mClock = clock;
    mDirectoryIdGenerator = new InodeDirectoryIdGenerator(mBlockMaster);
    mUfsManager = masterContext.getUfsManager();
    mMountTable = new MountTable(mUfsManager, getRootMountInfo(mUfsManager), mClock);
    mInodeLockManager = new InodeLockManager();
    InodeStore inodeStore = masterContext.getInodeStoreFactory().apply(mInodeLockManager);
    mInodeStore = new DelegatingReadOnlyInodeStore(inodeStore);
    mInodeTree = new InodeTree(inodeStore, mBlockMaster,
        mDirectoryIdGenerator, mMountTable, mInodeLockManager);

    // TODO(gene): Handle default config value for whitelist.
    mWhitelist = new PrefixList(Configuration.getList(PropertyKey.MASTER_WHITELIST));
    mPersistBlacklist = Configuration.isSet(PropertyKey.MASTER_PERSISTENCE_BLACKLIST)
        ? Configuration.getList(PropertyKey.MASTER_PERSISTENCE_BLACKLIST)
        : Collections.emptyList();

    mStateLockCallTracker = new CallTracker() {
      @Override
      public boolean isCancelled() {
        return masterContext.getStateLockManager().interruptCycleTicking();
      }

      @Override
      public Type getType() {
        return Type.STATE_LOCK_TRACKER;
      }
    };
    mPermissionChecker = new DefaultPermissionChecker(mInodeTree);
    mJobMasterClientPool = new JobMasterClientPool(JobMasterClientContext
        .newBuilder(ClientContext.create(Configuration.global())).build());
    mPersistRequests = new ConcurrentHashMap<>();
    mPersistJobs = new ConcurrentHashMap<>();
    mUfsAbsentPathCache = UfsAbsentPathCache.Factory.create(mMountTable, mClock);
    mUfsBlockLocationCache = UfsBlockLocationCache.Factory.create(mMountTable);
    mSyncManager = new ActiveSyncManager(mMountTable, this);
    mTimeSeriesStore = new TimeSeriesStore();
    mAccessTimeUpdater =
        Configuration.getBoolean(PropertyKey.MASTER_FILE_ACCESS_TIME_UPDATER_ENABLED)
            ? new AccessTimeUpdater(
                this, mInodeTree, masterContext.getJournalSystem()) : null;
    // Sync executors should allow core threads to time out
    mSyncPrefetchExecutor.allowCoreThreadTimeOut(true);
    mSyncMetadataExecutor.allowCoreThreadTimeOut(true);
    mActiveSyncMetadataExecutor.allowCoreThreadTimeOut(true);
    FileSystemContext schedulerFsContext = FileSystemContext.create();
    JournaledJobMetaStore jobMetaStore = new JournaledJobMetaStore(this);
    mScheduler = new Scheduler(new DefaultWorkerProvider(this, schedulerFsContext), jobMetaStore);
    mDefaultSyncProcess =  createSyncProcess(
        mInodeStore, mMountTable, mInodeTree, getSyncPathCache());

    // The mount table should come after the inode tree because restoring the mount table requires
    // that the inode tree is already restored.
    ArrayList<Journaled> journaledComponents = new ArrayList<Journaled>() {
      {
        add(mInodeTree);
        add(mDirectoryIdGenerator);
        add(mMountTable);
        add(mUfsManager);
        add(mSyncManager);
        add(jobMetaStore);
      }
    };
    mJournaledGroup = new JournaledGroup(journaledComponents, CheckpointName.FILE_SYSTEM_MASTER);

    resetState();
    Metrics.registerGauges(mUfsManager, mInodeTree);
    MetricsSystem.registerCachedGaugeIfAbsent(
        MetricsSystem.getMetricName(
            MetricKey.MASTER_METADATA_SYNC_PREFETCH_EXECUTOR_QUEUE_SIZE.getName()),
        () -> mSyncPrefetchExecutor.getQueue().size(), 2, TimeUnit.SECONDS);
    MetricsSystem.registerCachedGaugeIfAbsent(
        MetricsSystem.getMetricName(MetricKey.MASTER_METADATA_SYNC_EXECUTOR_QUEUE_SIZE.getName()),
        () -> mSyncMetadataExecutor.getQueue().size(), 2, TimeUnit.SECONDS);
    MetricsSystem.registerGaugeIfAbsent(
        MetricKey.MASTER_AUDIT_LOG_ENTRIES_SIZE.getName(),
        () -> mAsyncAuditLogWriter != null
            ? mAsyncAuditLogWriter.getAuditLogEntriesSize() : -1);
  }

  private static MountInfo getRootMountInfo(MasterUfsManager ufsManager) {
    try (CloseableResource<UnderFileSystem> resource = ufsManager.getRoot().acquireUfsResource()) {
      boolean shared = resource.get().isObjectStorage()
          && Configuration.getBoolean(PropertyKey.UNDERFS_OBJECT_STORE_MOUNT_SHARED_PUBLICLY);
      boolean readonly = Configuration.getBoolean(
          PropertyKey.MASTER_MOUNT_TABLE_ROOT_READONLY);
      String rootUfsUri = PathUtils.normalizePath(
          Configuration.getString(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS),
          AlluxioURI.SEPARATOR);
      Map<String, String> rootUfsConf =
          Configuration.getNestedProperties(PropertyKey.MASTER_MOUNT_TABLE_ROOT_OPTION)
              .entrySet().stream()
              .filter(entry -> entry.getValue() != null)
              .collect(Collectors.toMap(Map.Entry::getKey,
                  entry -> String.valueOf(entry.getValue())));
      MountPOptions mountOptions = MountContext
          .mergeFrom(MountPOptions.newBuilder().setShared(shared).setReadOnly(readonly)
                  .putAllProperties(rootUfsConf))
          .getOptions().build();
      return new MountInfo(new AlluxioURI(MountTable.ROOT),
          new AlluxioURI(rootUfsUri), IdUtils.ROOT_MOUNT_ID, mountOptions);
    }
  }

  @Override
  public Map<ServiceType, GrpcService> getServices() {
    Map<ServiceType, GrpcService> services = new HashMap<>();
    services.put(ServiceType.FILE_SYSTEM_MASTER_CLIENT_SERVICE, new GrpcService(ServerInterceptors
        .intercept(new FileSystemMasterClientServiceHandler(this, mScheduler),
            new ClientContextServerInjector())));
    services.put(ServiceType.FILE_SYSTEM_MASTER_JOB_SERVICE, new GrpcService(ServerInterceptors
        .intercept(new FileSystemMasterJobServiceHandler(this),
            new ClientContextServerInjector())));
    services.put(ServiceType.FILE_SYSTEM_MASTER_WORKER_SERVICE, new GrpcService(ServerInterceptors
        .intercept(new FileSystemMasterWorkerServiceHandler(this),
            new ClientContextServerInjector())));
    return services;
  }

  @Override
  public String getName() {
    return Constants.FILE_SYSTEM_MASTER_NAME;
  }

  @Override
  public Set<Class<? extends Server>> getDependencies() {
    return DEPS;
  }

  @Override
  public Journaled getDelegate() {
    return mJournaledGroup;
  }

  @Override
  public JournalContext createJournalContext() throws UnavailableException {
    return createJournalContext(false);
  }

  /**
   * Creates a journal context.
   * @param useMergeJournalContext if set to true, if possible, a journal context that merges
   *  journal entries and holds them until the context is closed. If set to false,
   *  a normal journal context will be returned.
   * @return the journal context
   */
  @VisibleForTesting
  JournalContext createJournalContext(boolean useMergeJournalContext)
      throws UnavailableException {
    JournalContext context = super.createJournalContext();
    if (!(mMergeInodeJournals && useMergeJournalContext)) {
      return context;
    }
    return new FileSystemMergeJournalContext(
        context, new FileSystemJournalEntryMerger()
    );
  }

  @Override
  public void start(Boolean isPrimary) throws IOException {
    super.start(isPrimary);
    if (isPrimary) {
      LOG.info("Starting fs master as primary");

      InodeDirectory root = mInodeTree.getRoot();
      if (root == null) {
        try (JournalContext context = createJournalContext()) {
          mInodeTree.initializeRoot(
              SecurityUtils.getOwner(mMasterContext.getUserState()),
              SecurityUtils.getGroup(mMasterContext.getUserState(), Configuration.global()),
              ModeUtils.applyDirectoryUMask(Mode.createFullAccess(),
                  Configuration.getString(
                      PropertyKey.SECURITY_AUTHORIZATION_PERMISSION_UMASK)),
              context);
        }
      } else if (!Configuration.getBoolean(PropertyKey.MASTER_SKIP_ROOT_ACL_CHECK)) {
        // For backwards-compatibility:
        // Empty root owner indicates that previously the master had no security. In this case, the
        // master is allowed to be started with security turned on.
        String serverOwner = SecurityUtils.getOwner(mMasterContext.getUserState());
        if (SecurityUtils.isSecurityEnabled(Configuration.global())
            && !root.getOwner().isEmpty() && !root.getOwner().equals(serverOwner)) {
          // user is not the previous owner
          throw new PermissionDeniedException(ExceptionMessage.PERMISSION_DENIED.getMessage(String
              .format("Unauthorized user on root. inode owner: %s current user: %s",
                  root.getOwner(), serverOwner)));
        }
      }

      // Initialize the ufs manager from the mount table.
      for (String key : mMountTable.getMountTable().keySet()) {
        if (key.equals(MountTable.ROOT)) {
          continue;
        }
        MountInfo mountInfo = mMountTable.getMountTable().get(key);
        UnderFileSystemConfiguration ufsConf = new UnderFileSystemConfiguration(
            Configuration.global(), mountInfo.getOptions().getReadOnly())
            .createMountSpecificConf(mountInfo.getOptions().getPropertiesMap());
        mUfsManager.addMount(mountInfo.getMountId(), mountInfo.getUfsUri(), ufsConf);
      }
      // Startup Checks and Periodic Threads.

      // Rebuild the list of persist jobs (mPersistJobs) and map of pending persist requests
      // (mPersistRequests)
      long persistInitialIntervalMs =
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_INITIAL_INTERVAL_MS);
      long persistMaxIntervalMs =
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_INTERVAL_MS);
      long persistMaxWaitMs =
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_TOTAL_WAIT_TIME_MS);

      for (Long id : mInodeTree.getToBePersistedIds()) {
        Inode inode = mInodeStore.get(id).get();
        if (inode.isDirectory()
            || !inode.asFile().isCompleted() // When file is completed it is added to persist reqs
            || inode.getPersistenceState() != PersistenceState.TO_BE_PERSISTED
            || inode.asFile().getShouldPersistTime() == Constants.NO_AUTO_PERSIST) {
          continue;
        }
        InodeFile inodeFile = inode.asFile();
        if (inodeFile.getPersistJobId() == Constants.PERSISTENCE_INVALID_JOB_ID) {
          mPersistRequests.put(inodeFile.getId(),
              new alluxio.time.ExponentialTimer(
                persistInitialIntervalMs,
                persistMaxIntervalMs,
                getPersistenceWaitTime(inodeFile.getShouldPersistTime()),
                persistMaxWaitMs));
        } else {
          AlluxioURI path;
          try {
            path = mInodeTree.getPath(inodeFile);
          } catch (FileDoesNotExistException e) {
            LOG.error("Failed to determine path for inode with id {}", id, e);
            continue;
          }
          addPersistJob(id, inodeFile.getPersistJobId(),
              getPersistenceWaitTime(inodeFile.getShouldPersistTime()),
              path, inodeFile.getTempUfsPath());
        }
      }
      if (Configuration
          .getBoolean(PropertyKey.MASTER_STARTUP_BLOCK_INTEGRITY_CHECK_ENABLED)) {
        validateInodeBlocks(true);
      }

      long blockIntegrityCheckInterval = Configuration
          .getMs(PropertyKey.MASTER_PERIODIC_BLOCK_INTEGRITY_CHECK_INTERVAL);

      if (blockIntegrityCheckInterval > 0) { // negative or zero interval implies disabled
        getExecutorService().submit(
            new HeartbeatThread(HeartbeatContext.MASTER_BLOCK_INTEGRITY_CHECK,
                new BlockIntegrityChecker(this), () ->
                new FixedIntervalSupplier(Configuration.getMs(
                    PropertyKey.MASTER_PERIODIC_BLOCK_INTEGRITY_CHECK_INTERVAL)),
                Configuration.global(), mMasterContext.getUserState()));
      }
      getExecutorService().submit(
          new HeartbeatThread(HeartbeatContext.MASTER_TTL_CHECK,
              new InodeTtlChecker(this, mInodeTree),
              () -> new FixedIntervalSupplier(
                  Configuration.getMs(PropertyKey.MASTER_TTL_CHECKER_INTERVAL_MS)),
              Configuration.global(), mMasterContext.getUserState()));
      getExecutorService().submit(
          new HeartbeatThread(HeartbeatContext.MASTER_LOST_FILES_DETECTION,
              new LostFileDetector(this, mBlockMaster, mInodeTree),
              () -> new FixedIntervalSupplier(
                  Configuration.getMs(PropertyKey.MASTER_LOST_WORKER_FILE_DETECTION_INTERVAL)),
              Configuration.global(), mMasterContext.getUserState()));
      mReplicationCheckHeartbeatThread = new HeartbeatThread(
          HeartbeatContext.MASTER_REPLICATION_CHECK,
          new alluxio.master.file.replication.ReplicationChecker(mInodeTree, mBlockMaster,
              mSafeModeManager, mJobMasterClientPool),
          () -> new FixedIntervalSupplier(
              Configuration.getMs(PropertyKey.MASTER_REPLICATION_CHECK_INTERVAL_MS)),
          Configuration.global(), mMasterContext.getUserState());
      getExecutorService().submit(mReplicationCheckHeartbeatThread);
      getExecutorService().submit(
          new HeartbeatThread(HeartbeatContext.MASTER_PERSISTENCE_SCHEDULER,
              new PersistenceScheduler(),
              () -> new FixedIntervalSupplier(
                  Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_SCHEDULER_INTERVAL_MS)),
              Configuration.global(), mMasterContext.getUserState()));
      mPersistCheckerPool =
          new java.util.concurrent.ThreadPoolExecutor(PERSIST_CHECKER_POOL_THREADS,
              PERSIST_CHECKER_POOL_THREADS, 1, java.util.concurrent.TimeUnit.MINUTES,
              new LinkedBlockingQueue<>(),
              alluxio.util.ThreadFactoryUtils.build("Persist-Checker-%d", true));
      mPersistCheckerPool.allowCoreThreadTimeOut(true);
      getExecutorService().submit(
          new HeartbeatThread(HeartbeatContext.MASTER_PERSISTENCE_CHECKER,
              new PersistenceChecker(),
              () -> new FixedIntervalSupplier(
                  Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_CHECKER_INTERVAL_MS)),
              Configuration.global(), mMasterContext.getUserState()));
      getExecutorService().submit(
          new HeartbeatThread(HeartbeatContext.MASTER_METRICS_TIME_SERIES,
              new TimeSeriesRecorder(),
              () -> new FixedIntervalSupplier(
                  Configuration.getMs(PropertyKey.MASTER_METRICS_TIME_SERIES_INTERVAL)),
              Configuration.global(), mMasterContext.getUserState()));
      if (Configuration.getBoolean(PropertyKey.UNDERFS_CLEANUP_ENABLED)) {
        getExecutorService().submit(
            new HeartbeatThread(HeartbeatContext.MASTER_UFS_CLEANUP, new UfsCleaner(this),
                () -> new FixedIntervalSupplier(
                    Configuration.getMs(PropertyKey.UNDERFS_CLEANUP_INTERVAL)),
                Configuration.global(), mMasterContext.getUserState()));
      }
      if (mAccessTimeUpdater != null) {
        mAccessTimeUpdater.start();
      }
      mSyncManager.start();
      mScheduler.start();
    }
    /**
     * The audit logger will be running all the time, and an operation checks whether
     * to enable audit logs in {@link #createAuditContext}. So audit log can be turned on/off
     * at runtime by updating the property key.
     */
    mAsyncAuditLogWriter = new AsyncUserAccessAuditLogWriter("AUDIT_LOG");
    mAsyncAuditLogWriter.start();
  }

  @Override
  public void stop() throws IOException {
    LOG.info("Next directory id before close: {}", mDirectoryIdGenerator.peekDirectoryId());
    if (mAsyncAuditLogWriter != null) {
      mAsyncAuditLogWriter.stop();
      mAsyncAuditLogWriter = null;
    }
    mSyncManager.stop();
    if (mAccessTimeUpdater != null) {
      mAccessTimeUpdater.stop();
    }
    mScheduler.stop();
    super.stop();
  }

  @Override
  public void close() throws IOException {
    super.close();
    mInodeTree.close();
    mInodeLockManager.close();
    try {
      mSyncMetadataExecutor.shutdownNow();
      mSyncMetadataExecutor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Failed to wait for metadata sync executor to shut down.");
    }

    try {
      mSyncPrefetchExecutor.shutdownNow();
      mSyncPrefetchExecutor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Failed to wait for ufs prefetch executor to shut down.");
    }

    try {
      mActiveSyncMetadataExecutor.shutdownNow();
      mActiveSyncMetadataExecutor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Failed to wait for active sync executor to shut down.");
    }
  }

  @Override
  public void validateInodeBlocks(boolean repair) throws UnavailableException {
    mBlockMaster.validateBlocks((blockId) -> {
      long fileId = IdUtils.fileIdFromBlockId(blockId);
      return mInodeTree.inodeIdExists(fileId);
    }, repair);
  }

  @Override
  public void cleanupUfs() {
    for (Map.Entry<String, MountInfo> mountPoint : mMountTable.getMountTable().entrySet()) {
      MountInfo info = mountPoint.getValue();
      if (info.getOptions().getReadOnly()) {
        continue;
      }
      try (CloseableResource<UnderFileSystem> ufsResource =
          mUfsManager.get(info.getMountId()).acquireUfsResource()) {
        ufsResource.get().cleanup();
      } catch (UnavailableException | NotFoundException e) {
        LOG.error("No UFS cached for {}", info, e);
      } catch (IOException e) {
        LOG.error("Failed in cleanup UFS {}.", info, e);
      }
    }
  }

  @Override
  public long getFileId(AlluxioURI path) throws AccessControlException, UnavailableException {
    return getFileIdInternal(path, true);
  }

  private long getFileIdInternal(AlluxioURI path, boolean checkPermission)
      throws AccessControlException, UnavailableException {
    try (RpcContext rpcContext = createRpcContext()) {
      /*
      In order to prevent locking twice on RPCs where metadata does _not_ need to be loaded, we use
      a two-step scheme as an optimization to prevent the extra lock. loadMetadataIfNotExists
      requires a lock on the tree to determine if the path should be loaded before executing. To
      prevent the extra lock, we execute the RPC as normal and use a conditional check in the
      main body of the function to determine whether control flow should be shifted out of the
      RPC logic and back to the loadMetadataIfNotExists function.

      If loadMetadataIfNotExists runs, then the next pass into the main logic body should
      continue as normal. This may present a slight decrease in performance for newly-loaded
      metadata, but it is better than affecting the most common case where metadata is not being
      loaded.
       */
      LoadMetadataContext lmCtx = LoadMetadataContext.mergeFrom(
          LoadMetadataPOptions.newBuilder().setCreateAncestors(true));
      boolean run = true;
      boolean loadMetadata = false;
      while (run) {
        run = false;
        if (loadMetadata) {
          loadMetadataIfNotExist(rpcContext, path, lmCtx);
        }
        try (LockedInodePath inodePath = mInodeTree
            .lockInodePath(path, LockPattern.READ, rpcContext.getJournalContext())
        ) {
          if (checkPermission) {
            mPermissionChecker.checkPermission(Mode.Bits.READ, inodePath);
          }
          if (!loadMetadata && shouldLoadMetadataIfNotExists(inodePath, lmCtx)) {
            loadMetadata = true;
            run = true;
            continue;
          }
          mInodeTree.ensureFullInodePath(inodePath);
          return inodePath.getInode().getId();
        } catch (InvalidPathException | FileDoesNotExistException e) {
          return IdUtils.INVALID_FILE_ID;
        }
      }
    } catch (InvalidPathException e) {
      return IdUtils.INVALID_FILE_ID;
    }
    return IdUtils.INVALID_FILE_ID;
  }

  @Override
  public FileInfo getFileInfo(long fileId)
      throws FileDoesNotExistException, AccessControlException, UnavailableException {
    Metrics.GET_FILE_INFO_OPS.inc();
    try (LockedInodePath inodePath = mInodeTree.lockFullInodePath(
        fileId, LockPattern.READ, NoopJournalContext.INSTANCE)
    ) {
      return getFileInfoInternal(inodePath);
    }
  }

  @Override
  public FileInfo getFileInfo(AlluxioURI path, GetStatusContext context)
      throws FileDoesNotExistException, InvalidPathException, AccessControlException, IOException {
    if (context.getOptions().hasDirectUfsAccess() && context.getOptions().getDirectUfsAccess()) {
      return getFileInfoFromUfs(path);
    }

    Metrics.GET_FILE_INFO_OPS.inc();
    boolean ufsAccessed = false;
    long opTimeMs = mClock.millis();
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("getFileInfo", path, null, null)) {

      if (!syncMetadata(rpcContext, path, context.getOptions().getCommonOptions(),
          DescendantType.NONE, auditContext, LockedInodePath::getInodeOrNull).equals(NOT_NEEDED)) {
        // If synced, do not load metadata.
        context.getOptions().setLoadMetadataType(LoadMetadataPType.NEVER);
        ufsAccessed = true;
      }
      LoadMetadataContext lmCtx = LoadMetadataContext.mergeFrom(
          LoadMetadataPOptions.newBuilder().setCreateAncestors(true)
              .setLoadType(context.getOptions().getLoadMetadataType()).setCommonOptions(
              FileSystemMasterCommonPOptions.newBuilder()
                  .setTtl(context.getOptions().getCommonOptions().getTtl())
                  .setTtlAction(context.getOptions().getCommonOptions().getTtlAction())));
      /**
       * See the comments in {@link #getFileIdInternal(AlluxioURI, boolean)} for an explanation
       * on why the loop here is required.
       */
      boolean run = true;
      boolean loadMetadata = false;
      FileInfo ret = null;
      while (run) {
        run = false;
        if (loadMetadata) {
          checkLoadMetadataOptions(context.getOptions().getLoadMetadataType(), path);
          loadMetadataIfNotExist(rpcContext, path, lmCtx);
          ufsAccessed = true;
        }

        LockingScheme lockingScheme = new LockingScheme(path, LockPattern.READ, false);
        try (LockedInodePath inodePath = mInodeTree
            .lockInodePath(lockingScheme, rpcContext.getJournalContext())
        ) {
          auditContext.setSrcInode(inodePath.getInodeOrNull());
          try {
            mPermissionChecker.checkParentPermission(Mode.Bits.EXECUTE, inodePath);
          } catch (AccessControlException e) {
            auditContext.setAllowed(false);
            throw e;
          }

          if (!loadMetadata && shouldLoadMetadataIfNotExists(inodePath, lmCtx)) {
            loadMetadata = true;
            run = true;
            continue;
          }

          ensureFullPathAndUpdateCache(inodePath);

          FileInfo fileInfo = getFileInfoInternal(inodePath);
          if (!fileInfo.isFolder() && (!fileInfo.isCompleted())) {
            LOG.debug("File {} is not yet completed. getStatus will see incomplete metadata.",
                fileInfo.getPath());
          }
          if (ufsAccessed) {
            MountTable.Resolution resolution = mMountTable.resolve(inodePath.getUri());
            Metrics.getUfsOpsSavedCounter(resolution.getUfsMountPointUri(),
                Metrics.UFSOps.GET_FILE_INFO).dec();
          }
          Mode.Bits accessMode = Mode.Bits.fromProto(context.getOptions().getAccessMode());
          if (context.getOptions().getUpdateTimestamps() && context.getOptions().hasAccessMode()
              && (accessMode.imply(Mode.Bits.READ) || accessMode.imply(Mode.Bits.WRITE))) {
            updateAccessTime(rpcContext, inodePath.getInode(), opTimeMs);
          }
          auditContext.setSrcInode(inodePath.getInode()).setSucceeded(true);
          ret = fileInfo;
        }
      }
      return ret;
    }
  }

  private FileInfo getFileInfoFromUfs(AlluxioURI path) {
    try {
      MountTable.Resolution resolution = mMountTable.resolve(path);
      try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
        UnderFileSystem ufs = ufsResource.get();
        String ufsPath = resolution.getUri().getPath();
        if (ufs == null) {
          throw new RuntimeException("Ufs not found");
        }
        UfsStatus ufsStatus;
        try {
          ufsStatus = ufs.getStatus(ufsPath);
          FileInfo fi = FileInfo.fromUfsStatus(ufsStatus);
          // Set a dummy TTL action to avoid NPE
          fi.setTtlAction(TtlAction.DELETE);
          return fi;
        } catch (FileNotFoundException e) {
          throw new RuntimeException("File not found");
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public long getMountIdFromUfsPath(AlluxioURI ufsPath) {
    return getMountTable().reverseResolve(ufsPath).getMountInfo().getMountId();
  }

  private FileInfo getFileInfoInternal(LockedInodePath inodePath)
      throws UnavailableException, FileDoesNotExistException {
    return getFileInfoInternal(inodePath, null, false);
  }

  /**
   * @param inodePath the {@link LockedInodePath} to get the {@link FileInfo} for
   * @return the {@link FileInfo} for the given inode
   */
  private FileInfo getFileInfoInternal(LockedInodePath inodePath, Counter counter,
      boolean excludeMountInfo)
      throws FileDoesNotExistException, UnavailableException {
    int inMemoryPercentage;
    int inAlluxioPercentage;
    Inode inode = inodePath.getInode();
    AlluxioURI uri = inodePath.getUri();
    FileInfo fileInfo = inode.generateClientFileInfo(uri.toString());
    if (fileInfo.isFolder()) {
      fileInfo.setLength(inode.asDirectory().getChildCount());
    }
    if (inode.isFile()) {
      InodeFile inodeFile = inode.asFile();
      List<BlockInfo> blockInfos = mBlockMaster.getBlockInfoList(inodeFile.getBlockIds());
      inMemoryPercentage = getFileInMemoryPercentageInternal(inodeFile, blockInfos);
      inAlluxioPercentage = getFileInAlluxioPercentageInternal(inodeFile, blockInfos);
      fileInfo.setInMemoryPercentage(inMemoryPercentage);
      fileInfo.setInAlluxioPercentage(inAlluxioPercentage);

      List<FileBlockInfo> fileBlockInfos = new ArrayList<>(blockInfos.size());
      for (BlockInfo blockInfo : blockInfos) {
        fileBlockInfos.add(generateFileBlockInfo(inodePath, blockInfo, excludeMountInfo));
      }
      fileInfo.setFileBlockInfos(fileBlockInfos);
    }
    // Rehydrate missing block-infos for persisted files.
    if (fileInfo.isCompleted()
          && fileInfo.getBlockIds().size() > fileInfo.getFileBlockInfos().size()
          && inode.isPersisted()) {
      List<Long> missingBlockIds = fileInfo.getBlockIds().stream()
          .filter((bId) -> fileInfo.getFileBlockInfo(bId) != null).collect(Collectors.toList());

      LOG.warn("BlockInfo missing for file: {}. BlockIdsWithMissingInfos: {}", inodePath.getUri(),
          missingBlockIds.stream().map(Object::toString).collect(Collectors.joining(",")));
      // Remove old block metadata from block-master before re-committing.
      mBlockMaster.removeBlocks(fileInfo.getBlockIds(), true);
      // Commit all the file blocks (without locations) so the metadata for the block exists.
      commitBlockInfosForFile(
          fileInfo.getBlockIds(), fileInfo.getLength(), fileInfo.getBlockSizeBytes(), null);
      // Reset file-block-info list with the new list.
      try {
        fileInfo.setFileBlockInfos(getFileBlockInfoListInternal(inodePath, excludeMountInfo));
      } catch (InvalidPathException e) {
        throw new FileDoesNotExistException(
            String.format("Hydration failed for file: %s", inodePath.getUri()), e);
      }
    }
    fileInfo.setXAttr(inode.getXAttr());
    if (!excludeMountInfo) {
      MountTable.Resolution resolution;
      try {
        resolution = mMountTable.resolve(uri);
      } catch (InvalidPathException e) {
        throw new FileDoesNotExistException(e.getMessage(), e);
      }
      AlluxioURI resolvedUri = resolution.getUri();
      fileInfo.setUfsPath(resolvedUri.toString());
      fileInfo.setMountId(resolution.getMountId());
      if (counter == null) {
        Metrics.getUfsOpsSavedCounter(resolution.getUfsMountPointUri(),
            Metrics.UFSOps.GET_FILE_INFO).inc();
      } else {
        counter.inc();
      }
    }

    Metrics.FILE_INFOS_GOT.inc();
    return fileInfo;
  }

  @Override
  public PersistenceState getPersistenceState(long fileId) throws FileDoesNotExistException {
    try (LockedInodePath inodePath = mInodeTree.lockFullInodePath(
        fileId, LockPattern.READ, NoopJournalContext.INSTANCE)
    ) {
      return inodePath.getInode().getPersistenceState();
    }
  }

  @Override
  public void listStatus(AlluxioURI path, ListStatusContext context,
      ResultStream<FileInfo> resultStream)
      throws AccessControlException, FileDoesNotExistException, InvalidPathException, IOException {
    Metrics.GET_FILE_INFO_OPS.inc();
    LockingScheme lockingScheme = new LockingScheme(path, LockPattern.READ, false);
    boolean ufsAccessed = false;
    // List status might journal inode access time update journals.
    // We want these journals to be added to the async writer immediately instead of being merged.
    try (RpcContext rpcContext = createNonMergingJournalRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("listStatus", path, null, null)) {

      DescendantType descendantType =
          context.getOptions().getRecursive() ? DescendantType.ALL : DescendantType.ONE;
      if (!syncMetadata(rpcContext, path, context.getOptions().getCommonOptions(), descendantType,
          auditContext, LockedInodePath::getInodeOrNull).equals(NOT_NEEDED)) {
        // If synced, do not load metadata.
        context.getOptions().setLoadMetadataType(LoadMetadataPType.NEVER);
        ufsAccessed = true;
      }
      /**
       * See the comments in {@link #getFileIdInternal(AlluxioURI, boolean)} for an explanation
       * on why the loop here is required.
       */
      DescendantType loadDescendantType;
      if (context.getOptions().getLoadMetadataType() == LoadMetadataPType.NEVER) {
        loadDescendantType = DescendantType.NONE;
      } else if (context.getOptions().getRecursive()) {
        loadDescendantType = DescendantType.ALL;
      } else {
        loadDescendantType = DescendantType.ONE;
      }
      // load metadata for 1 level of descendants, or all descendants if recursive
      LoadMetadataContext loadMetadataContext = LoadMetadataContext.mergeFrom(
          LoadMetadataPOptions.newBuilder().setCreateAncestors(true)
              .setLoadType(context.getOptions().getLoadMetadataType())
              .setLoadDescendantType(GrpcUtils.toProto(loadDescendantType)).setCommonOptions(
              FileSystemMasterCommonPOptions.newBuilder()
                  .setTtl(context.getOptions().getCommonOptions().getTtl())
                  .setTtlAction(context.getOptions().getCommonOptions().getTtlAction())));
      boolean loadMetadata = false;
      boolean run = true;
      while (run) {
        run = false;
        if (loadMetadata && !context.isDisableMetadataSync()) {
          loadMetadataIfNotExist(rpcContext, path, loadMetadataContext);
          ufsAccessed = true;
        }
        // If doing a partial listing, then before we take the locks we must use the offset
        // Inode ID to compute the names of the path at where we should start the partial listing.
        List<String> partialPathNames = ListStatusPartial.checkPartialListingOffset(
            mInodeTree, path, context);
        // We just synced; the new lock pattern should not sync.
        try (LockedInodePath inodePath =
                 mInodeTree.lockInodePath(lockingScheme, rpcContext.getJournalContext())) {
          auditContext.setSrcInode(inodePath.getInodeOrNull());
          try {
            mPermissionChecker.checkPermission(Mode.Bits.READ, inodePath);
          } catch (AccessControlException e) {
            auditContext.setAllowed(false);
            throw e;
          }
          if (!loadMetadata && !context.isDisableMetadataSync()) {
            Inode inode;
            boolean isLoaded = true;
            if (inodePath.fullPathExists()) {
              inode = inodePath.getInode();
              if (inode.isDirectory() && !context.getOptions().getDisableAreDescendantsLoadedCheck()
                  && context.getOptions().getLoadMetadataType() != LoadMetadataPType.ALWAYS) {
                InodeDirectory inodeDirectory = inode.asDirectory();
                isLoaded = inodeDirectory.isDirectChildrenLoaded();
                if (context.getOptions().getRecursive()) {
                  isLoaded = areDescendantsLoaded(inodeDirectory);
                }
                if (isLoaded) {
                  // no need to load again.
                  loadMetadataContext.getOptions().setLoadDescendantType(LoadDescendantPType.NONE);
                }
              }
            } else {
              checkLoadMetadataOptions(context.getOptions().getLoadMetadataType(),
                  inodePath.getUri());
            }
            if (shouldLoadMetadataIfNotExists(inodePath, loadMetadataContext)) {
              loadMetadata = true;
              run = true;
              continue;
            }
          }
          ensureFullPathAndUpdateCache(inodePath);

          auditContext.setSrcInode(inodePath.getInode());
          MountTable.Resolution resolution = null;
          if (!context.getOptions().hasLoadMetadataOnly()
              || !context.getOptions().getLoadMetadataOnly()) {
            DescendantType descendantTypeForListStatus =
                (context.getOptions().getRecursive()) ? DescendantType.ALL : DescendantType.ONE;
            try {
              if (!context.getOptions().getExcludeMountInfo()) {
                resolution = mMountTable.resolve(path);
              }
            } catch (InvalidPathException e) {
              throw new FileDoesNotExistException(e.getMessage(), e);
            }
            // Compute paths for a partial listing
            partialPathNames = ListStatusPartial.computePartialListingPaths(path,
                context, partialPathNames, inodePath);
            List<String> prefixComponents = ListStatusPartial.checkPrefixListingPaths(
                context, partialPathNames);
            if (inodePath.getInode().isDirectory()) {
              if (context.getOptions().getRecursive()) {
                context.setTotalListings(-1);
              } else {
                context.setTotalListings(inodePath.getInode().asDirectory().getChildCount());
              }
            } else {
              context.setTotalListings(1);
            }
            // perform the listing
            listStatusInternal(context, rpcContext, inodePath, auditContext,
                descendantTypeForListStatus, resultStream, 0, resolution == null ? null :
                    Metrics.getUfsOpsSavedCounter(resolution.getUfsMountPointUri(),
                        Metrics.UFSOps.GET_FILE_INFO),
                partialPathNames, prefixComponents);
            if (!ufsAccessed && resolution != null) {
              Metrics.getUfsOpsSavedCounter(resolution.getUfsMountPointUri(),
                  Metrics.UFSOps.LIST_STATUS).inc();
            }
          }
          auditContext.setSucceeded(true);
          Metrics.FILE_INFOS_GOT.inc();
        }
      }
    }
  }

  @Override
  public List<FileInfo> listStatus(AlluxioURI path, ListStatusContext context)
      throws AccessControlException, FileDoesNotExistException, InvalidPathException, IOException {
    final List<FileInfo> fileInfos = new ArrayList<>();
    listStatus(path, context, fileInfos::add);
    return fileInfos;
  }

  /**
   * Lists the status of the path in {@link LockedInodePath}, possibly recursively depending on the
   * descendantType. The result is returned via a list specified by statusList, in postorder
   * traversal order.
   *
   * @param context call context
   * @param rpcContext the context for the RPC call
   * @param currInodePath the inode path to find the status
   * @param auditContext the audit context to return any access exceptions
   * @param descendantType if the currInodePath is a directory, how many levels of its descendant
   *        should be returned
   * @param resultStream the stream to receive individual results
   * @param depth internal use field for tracking depth relative to root item
   * @param partialPath used during partial listings, indicating where to start listing
   *                    at each depth. If this is null then the start of the partial path
   *                    has already been reached and the item should be listed. If a partial
   *                    listing is not being done this will always be null.
   * @param prefixComponents if filtering results by a prefix, the components of the prefix
   *                         split by the / delimiter
   */
  private void listStatusInternal(
      ListStatusContext context, RpcContext rpcContext, LockedInodePath currInodePath,
      AuditContext auditContext, DescendantType descendantType, ResultStream<FileInfo> resultStream,
      int depth, @Nullable Counter counter, List<String> partialPath,
      List<String> prefixComponents)
      throws FileDoesNotExistException, UnavailableException,
      AccessControlException, InvalidPathException {
    rpcContext.throwIfCancelled();
    Inode inode = currInodePath.getInode();
    if (context.donePartialListing()) {
      return;
    }

    // The item should be listed if:
    // 1. We are not doing a partial listing, or have reached the start of the partial listing
    //    (partialPath is empty)
    // 2. We have reached the last path component of the partial listing,
    //     and the item comes after this path component (in lexicographical sorted order)
    if (partialPath.isEmpty()
        || (partialPath.size() == depth
        && inode.getName().compareTo(partialPath.get(depth - 1)) > 0)) {
      // Add the item to the results before adding any of its children.
      // Listing a directory should not emit item for the directory itself (i.e. depth == 0).
      // Furthermore, the item should not be added if there are still components to the prefix
      // at this depth.
      if ((depth != 0 || inode.isFile()) && prefixComponents.size() <= depth) {
        if (context.listedItem()) {
          resultStream.submit(getFileInfoInternal(currInodePath, counter,
              context.getOptions().getExcludeMountInfo()));
        }
        if (context.isDoneListing()) {
          return;
        }
      }
    }

    if (inode.isDirectory() && descendantType != DescendantType.NONE) {
      try {
        // TODO(david): Return the error message when we do not have permission
        mPermissionChecker.checkPermission(Mode.Bits.EXECUTE, currInodePath);
      } catch (AccessControlException e) {
        auditContext.setAllowed(false);
        if (descendantType == DescendantType.ALL) {
          return;
        } else {
          throw e;
        }
      }
      if (partialPath.size() <= depth) {
        // if we have processed the full partial path, then we should list all children
        // in the remaining recursive calls, so we set partialPath to the empty list
        partialPath = Collections.emptyList();
      }
      updateAccessTime(rpcContext, inode, CommonUtils.getCurrentMs());
      DescendantType nextDescendantType = (descendantType == DescendantType.ALL)
          ? DescendantType.ALL : DescendantType.NONE;
      try (CloseableIterator<? extends Inode> childrenIterator = getChildrenIterator(
          inode, partialPath, prefixComponents, depth, context)) {
        // This is to generate a parsed child path components to be passed to lockChildPath
        String[] childComponentsHint = null;
        while (childrenIterator.hasNext()) {
          if (context.donePartialListing()) {
            return;
          }
          String childName = childrenIterator.next().getName();
          if (childComponentsHint == null) {
            String[] parentComponents = PathUtils.getPathComponents(
                currInodePath.getUri().getPath());
            childComponentsHint = new String[parentComponents.length + 1];
            System.arraycopy(parentComponents, 0, childComponentsHint, 0, parentComponents.length);
          }
          // TODO(david): Make extending InodePath more efficient
          childComponentsHint[childComponentsHint.length - 1] = childName;

          try (LockedInodePath childInodePath =
                   currInodePath.lockChildByName(
                       childName, LockPattern.READ, childComponentsHint, true)) {
            listStatusInternal(context, rpcContext, childInodePath, auditContext,
                nextDescendantType, resultStream, depth + 1, counter,
                partialPath, prefixComponents);
          } catch (InvalidPathException | FileDoesNotExistException e) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Path \"{}\" is invalid, has been ignored.",
                  PathUtils.concatPath("/", (Object) childComponentsHint));
            }
          }
          // Now that an item has been listed (meaning we have reached the start
          // of the partial listing), we no longer need to process the partial
          // path on remaining recursive calls
          partialPath = Collections.emptyList();
        }
      }
    }
  }

  private CloseableIterator<? extends Inode> getChildrenIterator(
      Inode inode, @Nullable List<String> partialPath, List<String> prefixComponents,
      int depth, ListStatusContext context) throws InvalidPathException {

    if (context.isPartialListing()) {
      return ListStatusPartial.getChildrenIterator(
          mInodeStore, inode, partialPath, prefixComponents, depth, context);
    } else {
      // Perform a full listing of all children sorted by name.
      return mInodeStore.getChildren(inode.asDirectory());
    }
  }

  /**
   * Checks the {@link LoadMetadataPType} to determine whether or not to proceed in loading
   * metadata. This method assumes that the path does not exist in Alluxio namespace, and will
   * throw an exception if metadata should not be loaded.
   *
   * @param loadMetadataType the {@link LoadMetadataPType} to check
   * @param path the path that does not exist in Alluxio namespace (used for exception message)
   */
  private void checkLoadMetadataOptions(LoadMetadataPType loadMetadataType, AlluxioURI path)
          throws FileDoesNotExistException {
    if (loadMetadataType == LoadMetadataPType.NEVER || (loadMetadataType == LoadMetadataPType.ONCE
            && mUfsAbsentPathCache.isAbsentSince(path, UfsAbsentPathCache.ALWAYS))) {
      throw new FileDoesNotExistException(ExceptionMessage.PATH_DOES_NOT_EXIST.getMessage(path));
    }
  }

  protected boolean areDescendantsLoaded(InodeDirectoryView inode) {
    if (!inode.isDirectChildrenLoaded()) {
      return false;
    }
    try (CloseableIterator<? extends Inode> it = mInodeStore.getChildren(inode)) {
      while (it.hasNext()) {
        Inode child = it.next();
        if (child.isDirectory()) {
          if (!areDescendantsLoaded(child.asDirectory())) {
            return false;
          }
        }
      }
    }
    return true;
  }

  /**
   * Checks to see if the entire path exists in Alluxio. Updates the absent cache if it does not
   * exist.
   *
   * @param inodePath the path to ensure
   */
  protected void ensureFullPathAndUpdateCache(LockedInodePath inodePath)
      throws InvalidPathException, FileDoesNotExistException {
    boolean exists = false;
    try {
      mInodeTree.ensureFullInodePath(inodePath);
      exists = true;
    } finally {
      if (!exists) {
        mUfsAbsentPathCache.processAsync(inodePath.getUri(), inodePath.getInodeList());
      }
    }
  }

  @Override
  public FileSystemMasterView getFileSystemMasterView() {
    return new FileSystemMasterView(this);
  }

  @Override
  public void checkAccess(AlluxioURI path, CheckAccessContext context)
      throws FileDoesNotExistException, InvalidPathException, AccessControlException, IOException {
    try (RpcContext rpcContext = createRpcContext(context);
         FileSystemMasterAuditContext auditContext =
             createAuditContext("checkAccess", path, null, null)) {
      Mode.Bits bits = Mode.Bits.fromProto(context.getOptions().getBits());
      syncMetadata(rpcContext,
          path,
          context.getOptions().getCommonOptions(),
          DescendantType.NONE,
          auditContext,
          LockedInodePath::getInodeOrNull
      );

      LockingScheme lockingScheme =
          createLockingScheme(path, context.getOptions().getCommonOptions(),
              LockPattern.READ);
      try (LockedInodePath inodePath
               = mInodeTree.lockInodePath(lockingScheme, rpcContext.getJournalContext())) {
        mPermissionChecker.checkPermission(bits, inodePath);
        if (!inodePath.fullPathExists()) {
          throw new FileDoesNotExistException(ExceptionMessage
              .PATH_DOES_NOT_EXIST.getMessage(path));
        }
        auditContext.setSucceeded(true);
      }
    }
  }

  @Override
  public List<AlluxioURI> checkConsistency(AlluxioURI path, CheckConsistencyContext context)
      throws AccessControlException, FileDoesNotExistException, InvalidPathException, IOException {
    List<AlluxioURI> inconsistentUris = new ArrayList<>();
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("checkConsistency", path, null, null)) {

      InodeSyncStream.SyncStatus syncStatus = syncMetadata(rpcContext,
          path,
          context.getOptions().getCommonOptions(),
          DescendantType.ALL,
          auditContext,
          LockedInodePath::getInodeOrNull);

      LockingScheme lockingScheme =
          createLockingScheme(path, context.getOptions().getCommonOptions(), LockPattern.READ);
      try (LockedInodePath parent = mInodeTree.lockInodePath(
          lockingScheme.getPath(), lockingScheme.getPattern(), rpcContext.getJournalContext())) {
        auditContext.setSrcInode(parent.getInodeOrNull());
        try {
          mPermissionChecker.checkPermission(Mode.Bits.READ, parent);
        } catch (AccessControlException e) {
          auditContext.setAllowed(false);
          throw e;
        }
        checkConsistencyRecursive(parent, inconsistentUris, false,
            syncStatus == OK);

        auditContext.setSucceeded(true);
      }
    }
    return inconsistentUris;
  }

  @Override
  public boolean exists(AlluxioURI path, ExistsContext context)
      throws AccessControlException, IOException {
    boolean exists = false;
    try (RpcContext rpcContext = createRpcContext(context);
         FileSystemMasterAuditContext auditContext =
             createAuditContext("exists", path, null, null)) {
      syncMetadata(
          rpcContext, path, context.getOptions().getCommonOptions(),
          DescendantType.ONE, auditContext, LockedInodePath::getInodeOrNull);

      LoadMetadataContext lmCtx = LoadMetadataContext.create(
          LoadMetadataPOptions.newBuilder()
              .setCommonOptions(context.getOptions().getCommonOptions())
              .setLoadType(context.getOptions().getLoadMetadataType()));
      /**
       * See the comments in {@link #getFileIdInternal(AlluxioURI, boolean)} for an explanation
       * on why the loop here is required.
       */
      boolean run = true;
      boolean loadMetadata = false;
      while (run) {
        run = false;
        if (loadMetadata) {
          try {
            checkLoadMetadataOptions(context.getOptions().getLoadMetadataType(), path);
          } catch (FileDoesNotExistException e) {
            return false;
          }
          loadMetadataIfNotExist(rpcContext, path, lmCtx);
        }

        try (LockedInodePath inodePath = mInodeTree.lockInodePath(
            createLockingScheme(path, context.getOptions().getCommonOptions(), LockPattern.READ),
            rpcContext.getJournalContext())
        ) {
          if (!loadMetadata && shouldLoadMetadataIfNotExists(inodePath, lmCtx)) {
            loadMetadata = true;
            run = true;
            continue;
          }
          try {
            if (mPermissionChecker instanceof DefaultPermissionChecker) {
              mPermissionChecker.checkParentPermission(Mode.Bits.EXECUTE, inodePath);
            }
          } catch (AccessControlException e) {
            auditContext.setAllowed(false);
            throw e;
          }
          auditContext.setSucceeded(true);
          exists = inodePath.fullPathExists();
        }
      }
    } catch (InvalidPathException e) {
      return false;
    }
    return exists;
  }

  private void checkConsistencyRecursive(LockedInodePath inodePath,
      List<AlluxioURI> inconsistentUris, boolean assertInconsistent, boolean metadataSynced)
          throws IOException, FileDoesNotExistException {
    Inode inode = inodePath.getInode();
    try {
      if (assertInconsistent || !checkConsistencyInternal(inodePath)) {
        inconsistentUris.add(inodePath.getUri());
        // If a dir in Alluxio is inconsistent with underlying storage,
        // we can assert the children is inconsistent.
        // If a file is inconsistent, please ignore this parameter cause it has no child node.
        assertInconsistent = true;
      }
      if (inode.isDirectory()) {
        InodeDirectory inodeDir = inode.asDirectory();
        CloseableIterator<? extends Inode> childrenIter = mInodeStore.getChildren(inodeDir);
        Iterable<Inode> children = StreamSupport.stream(Spliterators.spliteratorUnknownSize(
            childrenIter, 0), false).collect(Collectors.toList());
        childrenIter.close();
        for (Inode child : children) {
          try (LockedInodePath childPath = inodePath.lockChild(child, LockPattern.READ)) {
            checkConsistencyRecursive(childPath, inconsistentUris, assertInconsistent,
                metadataSynced);
          }
        }
        // If a file exists in ufs but not in alluxio,
        // it should be treated as an inconsistent file.
        // if it is a directory we could ignore the subpaths.
        // if the metadata has already been synced, then we could skip it.
        if (metadataSynced) {
          return;
        }
        MountTable.Resolution resolution = mMountTable.resolve(inodePath.getUri());
        UfsStatus[] statuses;
        try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
          UnderFileSystem ufs = ufsResource.get();
          String ufsPath = resolution.getUri().getPath();
          statuses = ufs.listStatus(ufsPath);
        }
        if (statuses != null) {
          HashSet<String> alluxioFileNames = Streams.stream(children)
              .map(Inode::getName)
              .collect(Collectors.toCollection(HashSet::new));
          Arrays.stream(statuses).forEach(status -> {
            if (!alluxioFileNames.contains(status.getName())) {
              inconsistentUris.add(inodePath.getUri().join(status.getName()));
            }
          });
        }
      }
    } catch (InvalidPathException e) {
      LOG.debug("Path \"{}\" is invalid, has been ignored.",
          PathUtils.concatPath(inodePath.getUri().getPath()));
    }
  }

  /**
   * Checks if a path is consistent between Alluxio and the underlying storage.
   * <p>
   * A path without a backing under storage is always consistent.
   * <p>
   * A not persisted path is considered consistent if:
   * 1. It does not shadow an object in the underlying storage.
   * <p>
   * A persisted path is considered consistent if:
   * 1. An equivalent object exists for its under storage path.
   * 2. The metadata of the Alluxio and under storage object are equal.
   *
   * @param inodePath the path to check. This must exist and be read-locked
   * @return true if the path is consistent, false otherwise
   */
  private boolean checkConsistencyInternal(LockedInodePath inodePath) throws InvalidPathException,
      IOException {
    Inode inode;
    try {
      inode = inodePath.getInode();
    } catch (FileDoesNotExistException e) {
      throw new RuntimeException(e); // already checked existence when creating the inodePath
    }
    MountTable.Resolution resolution = mMountTable.resolve(inodePath.getUri());
    try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
      UnderFileSystem ufs = ufsResource.get();
      String ufsPath = resolution.getUri().getPath();
      if (ufs == null) {
        return true;
      }
      if (!inode.isPersisted()) {
        return !ufs.exists(ufsPath);
      }
      UfsStatus ufsStatus;
      try {
        ufsStatus = ufs.getStatus(ufsPath);
      } catch (FileNotFoundException e) {
        return !inode.isPersisted();
      }
      // TODO(calvin): Evaluate which other metadata fields should be validated.
      if (inode.isDirectory()) {
        return ufsStatus.isDirectory();
      } else {
        String ufsFingerprint = Fingerprint.create(ufs.getUnderFSType(), ufsStatus).serialize();
        return ufsStatus.isFile()
            && (ufsFingerprint.equals(inode.asFile().getUfsFingerprint()))
            && ufsStatus instanceof UfsFileStatus
            && ((UfsFileStatus) ufsStatus).getContentLength() == inode.asFile().getLength();
      }
    }
  }

  @Override
  public void completeFile(AlluxioURI path, CompleteFileContext context)
      throws BlockInfoException, FileDoesNotExistException, InvalidPathException,
      InvalidFileSizeException, FileAlreadyCompletedException, AccessControlException,
      UnavailableException {
    if (isOperationComplete(context)) {
      Metrics.COMPLETED_OPERATION_RETRIED_COUNT.inc();
      LOG.warn("A completed \"completeFile\" operation has been retried. OperationContext={}",
          context);
      return;
    }
    Metrics.COMPLETE_FILE_OPS.inc();
    // No need to syncMetadata before complete.
    try (RpcContext rpcContext = createRpcContext(context);
         LockedInodePath inodePath = mInodeTree.lockFullInodePath(
             path, LockPattern.WRITE_INODE, rpcContext.getJournalContext()
         );
         FileSystemMasterAuditContext auditContext =
             createAuditContext("completeFile", path, null, inodePath.getInodeOrNull())) {
      Mode.Bits permissionNeed = Mode.Bits.WRITE;
      if (skipFileWritePermissionCheck(inodePath)) {
        // A file may be created with read-only permission, to enable writing to it
        // for the owner the permission needed is decreased here.
        // Please check Alluxio/alluxio/issues/15808 for details.
        permissionNeed = Mode.Bits.NONE;
      }
      try {
        mPermissionChecker.checkPermission(permissionNeed, inodePath);
      } catch (AccessControlException e) {
        auditContext.setAllowed(false);
        throw e;
      }
      // Even readonly mount points should be able to complete a file, for UFS reads in CACHE mode.
      completeFileInternal(rpcContext, inodePath, context);
      // Inode completion check is skipped because we know the file we completed is complete.
      if (context.getOptions().hasAsyncPersistOptions()) {
        scheduleAsyncPersistenceInternal(inodePath, ScheduleAsyncPersistenceContext
            .create(context.getOptions().getAsyncPersistOptionsBuilder()), rpcContext);
      }
      auditContext.setSucceeded(true);
      cacheOperation(context);
    }
  }

  /**
   * Creates a completed file for metadata sync.
   * This method is more efficient than a combination of individual
   * createFile() and completeFile() methods, with less journal entries generated and
   * less frequent metadata store updates.
   * @param rpcContext the rpc context for journaling
   * @param inodePath the inode path
   * @param createFileContext the create file context
   * @param ufsStatus the ufs status, used to generate fingerprint
   * @return the path of inodes to the created node
   */
  public List<Inode> createCompleteFileInternalForMetadataSync(
      RpcContext rpcContext, LockedInodePath inodePath, CreateFileContext createFileContext,
      UfsFileStatus ufsStatus
  )
      throws InvalidPathException, FileDoesNotExistException, FileAlreadyExistsException,
      BlockInfoException, IOException {
    long containerId = mBlockMaster.getNewContainerId();
    List<Long> blockIds = new ArrayList<>();

    int sequenceNumber = 0;
    long ufsLength = ufsStatus.getContentLength();
    long remainingBytes = ufsLength;
    long blockSize = createFileContext.getOptions().getBlockSizeBytes();
    while (remainingBytes > 0) {
      blockIds.add(BlockId.createBlockId(containerId, sequenceNumber));
      remainingBytes -= Math.min(remainingBytes, blockSize);
      sequenceNumber++;
    }
    createFileContext.setCompleteFileInfo(
        new CreateFileContext.CompleteFileInfo(containerId, ufsLength, blockIds)
    );
    createFileContext.setMetadataLoad(true, false);
    createFileContext.setFingerprint(getUfsFingerprint(inodePath.getUri(), ufsStatus, null));

    // Ufs absent cache is updated in the metadata syncer when a request processing is done,
    // so ufs absent cache is not updated here.
    List<Inode> inodes = createFileInternal(rpcContext, inodePath, createFileContext, false);

    commitBlockInfosForFile(blockIds, ufsLength, blockSize, rpcContext.getJournalContext());
    mUfsAbsentPathCache.processExisting(inodePath.getUri());
    return inodes;
  }

  /**
   * Completes a file. After a file is completed, it cannot be written to.
   *
   * @param rpcContext the rpc context
   * @param inodePath the {@link LockedInodePath} to complete
   * @param context the method context
   */
  void completeFileInternal(RpcContext rpcContext, LockedInodePath inodePath,
      CompleteFileContext context)
      throws InvalidPathException, FileDoesNotExistException, BlockInfoException,
      FileAlreadyCompletedException, InvalidFileSizeException, UnavailableException {
    Inode inode = inodePath.getInode();
    if (!inode.isFile()) {
      throw new FileDoesNotExistException(
          ExceptionMessage.PATH_MUST_BE_FILE.getMessage(inodePath.getUri()));
    }

    InodeFile fileInode = inode.asFile();
    List<Long> blockIdList = fileInode.getBlockIds();
    List<BlockInfo> blockInfoList = mBlockMaster.getBlockInfoList(blockIdList);
    if (!fileInode.isPersisted() && blockInfoList.size() != blockIdList.size()) {
      throw new BlockInfoException("Cannot complete a file without all the blocks committed");
    }

    // Iterate over all file blocks committed to Alluxio, computing the length and verify that all
    // the blocks (except the last one) is the same size as the file block size.
    long inAlluxioLength = 0;
    long fileBlockSize = fileInode.getBlockSizeBytes();
    for (int i = 0; i < blockInfoList.size(); i++) {
      BlockInfo blockInfo = blockInfoList.get(i);
      inAlluxioLength += blockInfo.getLength();
      if (i < blockInfoList.size() - 1 && blockInfo.getLength() != fileBlockSize) {
        throw new BlockInfoException(
            "Block index " + i + " has a block size smaller than the file block size (" + fileInode
                .getBlockSizeBytes() + ")");
      }
    }

    // If the file is persisted, its length is determined by UFS. Otherwise, its length is
    // determined by its size in Alluxio.
    long length = fileInode.isPersisted() ? context.getOptions().getUfsLength() : inAlluxioLength;

    String ufsFingerprint = Constants.INVALID_UFS_FINGERPRINT;
    String contentHash = null;
    if (fileInode.isPersisted()) {
      contentHash = context.getOptions().hasContentHash()
          ? context.getOptions().getContentHash() : null;
      ufsFingerprint = getUfsFingerprint(inodePath.getUri(), context.getUfsStatus(), contentHash);
    }

    completeFileInternal(rpcContext, inodePath, length, context.getOperationTimeMs(),
        ufsFingerprint, contentHash);
  }

  /**
   * @param rpcContext the rpc context
   * @param inodePath the {@link LockedInodePath} to complete
   * @param length the length to use
   * @param opTimeMs the operation time (in milliseconds)
   * @param ufsFingerprint the ufs fingerprint
   */
  private void completeFileInternal(RpcContext rpcContext, LockedInodePath inodePath, long length,
      long opTimeMs, String ufsFingerprint, String contentHash)
      throws FileDoesNotExistException, InvalidPathException, InvalidFileSizeException,
      FileAlreadyCompletedException, UnavailableException {
    Preconditions.checkState(inodePath.getLockPattern().isWrite());

    InodeFile inode = inodePath.getInodeFile();
    if (inode.isCompleted() && inode.getLength() != Constants.UNKNOWN_SIZE) {
      throw new FileAlreadyCompletedException(String
          .format("File %s has already been completed.", inode.getName()));
    }
    if (length < 0 && length != Constants.UNKNOWN_SIZE) {
      throw new InvalidFileSizeException(
          "File " + inode.getName() + " cannot have negative length: " + length);
    }
    Builder entry = UpdateInodeFileEntry.newBuilder()
        .setId(inode.getId())
        .setPath(inodePath.getUri().getPath())
        .setCompleted(true)
        .setLength(length);

    if (length == Constants.UNKNOWN_SIZE) {
      // TODO(gpang): allow unknown files to be multiple blocks.
      // If the length of the file is unknown, only allow 1 block to the file.
      length = inode.getBlockSizeBytes();
    }
    int sequenceNumber = 0;
    long remainingBytes = length;
    while (remainingBytes > 0) {
      entry.addSetBlocks(BlockId.createBlockId(inode.getBlockContainerId(), sequenceNumber));
      remainingBytes -= Math.min(remainingBytes, inode.getBlockSizeBytes());
      sequenceNumber++;
    }

    if (inode.isPersisted()) {
      // Commit all the file blocks (without locations) so the metadata for the block exists.
      commitBlockInfosForFile(entry.getSetBlocksList(), length, inode.getBlockSizeBytes(),
          rpcContext.getJournalContext());
      // The path exists in UFS, so it is no longer absent
      mUfsAbsentPathCache.processExisting(inodePath.getUri());
    }

    UpdateInodeEntry.Builder updateEntry = UpdateInodeEntry.newBuilder().setId(inode.getId());
    updateEntry.setUfsFingerprint(ufsFingerprint)
        .setLastModificationTimeMs(opTimeMs)
        .setLastAccessTimeMs(opTimeMs)
        .setOverwriteModificationTime(true);
    if (StringUtils.isNotEmpty(contentHash)) {
      updateEntry.putXAttr(Constants.ETAG_XATTR_KEY,
              ByteString.copyFrom(contentHash, StandardCharsets.UTF_8))
          .setXAttrUpdateStrategy(File.XAttrUpdateStrategy.UNION_REPLACE);
    }
    // We could introduce a concept of composite entries, so that these two entries could
    // be applied in a single call to applyAndJournal.
    mInodeTree.updateInode(rpcContext, updateEntry.build());
    mInodeTree.updateInodeFile(rpcContext, entry.build());

    Metrics.FILES_COMPLETED.inc();
  }

  String getUfsFingerprint(
      AlluxioURI uri, @Nullable UfsStatus ufsStatus, @Nullable String contentHash)
      throws InvalidPathException {
    // Retrieve the UFS fingerprint for this file.
    MountTable.Resolution resolution = mMountTable.resolve(uri);
    AlluxioURI resolvedUri = resolution.getUri();
    String ufsPath = resolvedUri.toString();
    try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
      UnderFileSystem ufs = ufsResource.get();
      if (ufsStatus == null) {
        return ufs.getParsedFingerprint(ufsPath, contentHash).serialize();
      } else {
        return Fingerprint.create(ufs.getUnderFSType(), ufsStatus).serialize();
      }
    }
  }

  /**
   * Queries InodeTree's operation cache and see if this operation has recently
   * been applied to its persistent state.
   *
   * @param opContext the operation context
   * @return {@code true} if this operation has recently been processed
   */
  private boolean isOperationComplete(OperationContext opContext) {
    return mInodeTree.isOperationComplete(opContext.getOperationId());
  }

  /**
   * Marks this operation as complete in InodeTree's internal retry cache.
   * This will be queried on each operation to avoid re-executing client RPCs.
   *
   * @param opContext the operation context
   */
  private void cacheOperation(OperationContext opContext) {
    mInodeTree.cacheOperation(opContext.getOperationId());
  }

  /**
   * Commits blocks to BlockMaster for given block list.
   *
   * @param blockIds the list of block ids
   * @param fileLength length of the file in bytes
   * @param blockSize the block size in bytes
   * @param context the journal context, if null a new context will be created
   */
  private void commitBlockInfosForFile(List<Long> blockIds, long fileLength, long blockSize,
      @Nullable JournalContext context) throws UnavailableException {
    long currLength = fileLength;
    for (long blockId : blockIds) {
      long currentBlockSize = Math.min(currLength, blockSize);
      // if we are not using the UFS journal system, we can use the same journal context
      // for the block info so that we do not have to create a new journal
      // context and flush again
      if (context != null && !(mJournalSystem instanceof UfsJournalSystem)) {
        mBlockMaster.commitBlockInUFS(blockId, currentBlockSize, context, false);
      } else {
        mBlockMaster.commitBlockInUFS(blockId, currentBlockSize);
      }
      currLength -= currentBlockSize;
    }
  }

  @Override
  public FileInfo createFile(AlluxioURI path, CreateFileContext context)
      throws AccessControlException, InvalidPathException, FileAlreadyExistsException,
      BlockInfoException, IOException, FileDoesNotExistException {
    if (isOperationComplete(context)) {
      Metrics.COMPLETED_OPERATION_RETRIED_COUNT.inc();
      LOG.warn("A completed \"createFile\" operation has been retried. OperationContext={}",
          context);
      return getFileInfo(path,
          GetStatusContext.create(GetStatusPOptions.newBuilder()
              .setCommonOptions(FileSystemMasterCommonPOptions.newBuilder().setSyncIntervalMs(-1))
              .setLoadMetadataType(LoadMetadataPType.NEVER).setUpdateTimestamps(false)));
    }
    Metrics.CREATE_FILES_OPS.inc();
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("createFile", path, null, null)) {

      syncMetadata(rpcContext,
          path,
          context.getOptions().getCommonOptions(),
          DescendantType.ONE,
          auditContext,
          (inodePath) -> context.getOptions().getRecursive()
              ? inodePath.getLastExistingInode() : inodePath.getParentInodeOrNull());

      LockingScheme lockingScheme =
          createLockingScheme(path, context.getOptions().getCommonOptions(),
              LockPattern.WRITE_EDGE);
      try (LockedInodePath inodePath = mInodeTree
          .lockInodePath(lockingScheme, rpcContext.getJournalContext())
      ) {
        auditContext.setSrcInode(inodePath.getParentInodeOrNull());
        if (context.getOptions().getRecursive()) {
          auditContext.setSrcInode(inodePath.getLastExistingInode());
        }
        try {
          mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, inodePath);
        } catch (AccessControlException e) {
          auditContext.setAllowed(false);
          throw e;
        }

        mMountTable.checkUnderWritableMountPoint(path);
        if (context.isPersisted()) {
          // Check if ufs is writable
          checkUfsMode(path, OperationType.WRITE);
        }
        deleteFileIfOverwrite(rpcContext, inodePath, context);
        createFileInternal(rpcContext, inodePath, context, true);
        auditContext.setSrcInode(inodePath.getInode()).setSucceeded(true);
        cacheOperation(context);
        return getFileInfoInternal(inodePath);
      }
    }
  }

  /**
   * @param rpcContext the rpc context
   * @param inodePath the path to be created
   * @param context the method context
   */
  private void deleteFileIfOverwrite(RpcContext rpcContext, LockedInodePath inodePath,
      CreateFileContext context)
      throws FileDoesNotExistException, IOException, InvalidPathException,
      FileAlreadyExistsException {
    if (inodePath.fullPathExists()) {
      Inode currentInode = inodePath.getInode();
      if (!context.getOptions().hasOverwrite() || !context.getOptions().getOverwrite()) {
        throw new FileAlreadyExistsException(
            ExceptionMessage.CANNOT_OVERWRITE_FILE_WITHOUT_OVERWRITE.getMessage(
                inodePath.getUri()));
      }
      // if the fullpath is a file and the option is to overwrite, delete it
      if (currentInode.isDirectory()) {
        throw new FileAlreadyExistsException(
            ExceptionMessage.CANNOT_OVERWRITE_DIRECTORY.getMessage(inodePath.getUri()));
      } else {
        try {
          deleteInternal(rpcContext, inodePath, DeleteContext.mergeFrom(
              DeletePOptions.newBuilder().setRecursive(true)
                  .setAlluxioOnly(!context.isPersisted())), true);
          inodePath.removeLastInode();
        } catch (DirectoryNotEmptyException e) {
          // Should not reach here
          throw new InvalidPathException(
              ExceptionMessage.CANNOT_OVERWRITE_DIRECTORY.getMessage(inodePath.getUri()));
        }
      }
    }
  }

  /**
   * @param rpcContext the rpc context
   * @param inodePath the path to be created
   * @param context the method context
   * @return the list of created inodes
   */
  List<Inode> createFileInternal(RpcContext rpcContext, LockedInodePath inodePath,
      CreateFileContext context, boolean updateUfsAbsentCache)
      throws InvalidPathException, FileAlreadyExistsException, BlockInfoException, IOException,
      FileDoesNotExistException {
    if (mWhitelist.inList(inodePath.getUri().toString())) {
      context.setCacheable(true);
    }
    // If the create succeeded, the list of created inodes will not be empty.
    List<Inode> created = mInodeTree.createPath(rpcContext, inodePath, context);

    if (context.isPersisted()) {
      // The path exists in UFS, so it is no longer absent. The ancestors exist in UFS, but the
      // actual file does not exist in UFS yet.
      if (updateUfsAbsentCache) {
        mUfsAbsentPathCache.processExisting(inodePath.getUri().getParent());
      }
    } else {
      MountTable.Resolution resolution = mMountTable.resolve(inodePath.getUri());
      Metrics.getUfsOpsSavedCounter(resolution.getUfsMountPointUri(),
          Metrics.UFSOps.CREATE_FILE).inc();
    }
    Metrics.FILES_CREATED.inc();
    return created;
  }

  @Override
  public long getNewBlockIdForFile(AlluxioURI path) throws FileDoesNotExistException,
      InvalidPathException, AccessControlException, UnavailableException {
    Metrics.GET_NEW_BLOCK_OPS.inc();
    try (RpcContext rpcContext = createRpcContext();
         LockedInodePath inodePath = mInodeTree.lockFullInodePath(
             path, LockPattern.WRITE_INODE, rpcContext.getJournalContext()
         );
         FileSystemMasterAuditContext auditContext =
            createAuditContext("getNewBlockIdForFile", path, null, inodePath.getInodeOrNull())) {
      Mode.Bits permissionNeed = Mode.Bits.WRITE;
      if (skipFileWritePermissionCheck(inodePath)) {
        // A file may be created with read-only permission, to enable writing to it
        // for the owner the permission needed is decreased here.
        // Please check Alluxio/alluxio/issues/15808 for details.
        permissionNeed = Mode.Bits.NONE;
      }
      try {
        mPermissionChecker.checkPermission(permissionNeed, inodePath);
      } catch (AccessControlException e) {
        auditContext.setAllowed(false);
        throw e;
      }
      Metrics.NEW_BLOCKS_GOT.inc();

      long blockId = mInodeTree.newBlock(rpcContext, NewBlockEntry.newBuilder()
          .setId(inodePath.getInode().getId())
          .build());
      auditContext.setSucceeded(true);
      return blockId;
    }
  }

  /**
   * In order to allow writing to read-only files when creating,
   * we need to skip write permission check for files sometimes.
   */
  private boolean skipFileWritePermissionCheck(LockedInodePath inodePath)
      throws FileDoesNotExistException {
    if (!inodePath.getInode().isFile() || inodePath.getInodeFile().isCompleted()) {
      return false;
    }
    String user;
    try {
      user = AuthenticatedClientUser.getClientUser(Configuration.global());
    } catch (AccessControlException e) {
      return false;
    }
    return user.equals(inodePath.getInodeFile().getOwner());
  }

  @Override
  public Map<String, MountPointInfo> getMountPointInfoSummary(boolean checkUfs) {
    String command = checkUfs ? "getMountPointInfo(checkUfs)" : "getMountPointInfo";
    try (FileSystemMasterAuditContext auditContext =
             createAuditContext(command, null, null, null)) {
      SortedMap<String, MountPointInfo> mountPoints = new TreeMap<>();
      for (Map.Entry<String, MountInfo> mountPoint : mMountTable.getMountTable().entrySet()) {
        mountPoints.put(mountPoint.getKey(),
            getDisplayMountPointInfo(mountPoint.getValue(), checkUfs));
      }
      auditContext.setSucceeded(true);
      return mountPoints;
    }
  }

  @Override
  public MountPointInfo getDisplayMountPointInfo(AlluxioURI path) throws InvalidPathException {
    if (!mMountTable.isMountPoint(path)) {
      throw new InvalidPathException(
          MessageFormat.format("Path \"{0}\" must be a mount point.", path));
    }
    return getDisplayMountPointInfo(mMountTable.getMountTable().get(path.toString()), true);
  }

  /**
   * Gets the mount point information for display from a mount information.
   *
   * @param checkUfs if true, invoke ufs to set ufs properties
   * @param mountInfo the mount information to transform
   * @return the mount point information
   */
  private MountPointInfo getDisplayMountPointInfo(MountInfo mountInfo, boolean checkUfs) {
    MountPointInfo info = mountInfo.toDisplayMountPointInfo();
    if (!checkUfs) {
      return info;
    }
    try (CloseableResource<UnderFileSystem> ufsResource =
             mUfsManager.get(mountInfo.getMountId()).acquireUfsResource()) {
      UnderFileSystem ufs = ufsResource.get();
      info.setUfsType(ufs.getUnderFSType());
      try {
        info.setUfsCapacityBytes(
            ufs.getSpace(info.getUfsUri(), UnderFileSystem.SpaceType.SPACE_TOTAL));
      } catch (IOException e) {
        LOG.warn("Cannot get total capacity of {}", info.getUfsUri(), e);
      }
      try {
        info.setUfsUsedBytes(
            ufs.getSpace(info.getUfsUri(), UnderFileSystem.SpaceType.SPACE_USED));
      } catch (IOException e) {
        LOG.warn("Cannot get used capacity of {}", info.getUfsUri(), e);
      }
    } catch (UnavailableException | NotFoundException e) {
      // We should never reach here
      LOG.error("No UFS cached for {}", info, e);
    }
    return info;
  }

  @Override
  public void delete(AlluxioURI path, DeleteContext context)
      throws IOException, FileDoesNotExistException, DirectoryNotEmptyException,
      InvalidPathException, AccessControlException {
    if (isOperationComplete(context)) {
      Metrics.COMPLETED_OPERATION_RETRIED_COUNT.inc();
      LOG.warn("A completed \"delete\" operation has been retried. OperationContext={}", context);
      return;
    }
    Metrics.DELETE_PATHS_OPS.inc();
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("delete", path, null, null)) {

      if (context.getOptions().getAlluxioOnly()) {
        LOG.debug("alluxio-only deletion on path {} skips metadata sync", path);
      } else {
        syncMetadata(rpcContext,
            path,
            context.getOptions().getCommonOptions(),
            context.getOptions().getRecursive() ? DescendantType.ALL : DescendantType.ONE,
            auditContext,
            LockedInodePath::getInodeOrNull
        );
      }

      LockingScheme lockingScheme =
          createLockingScheme(path, context.getOptions().getCommonOptions(),
              LockPattern.WRITE_EDGE);
      try (LockedInodePath inodePath = mInodeTree
              .lockInodePath(lockingScheme, rpcContext.getJournalContext())
      ) {
        mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, inodePath);

        // If the mount point is read only, we allow removing the in-Alluxio metadata and data
        // in order to load it from the UFS again.
        // This can happen if Alluxio is out-of-sync with the UFS.
        if (!context.getOptions().getAlluxioOnly()) {
          mMountTable.checkUnderWritableMountPoint(path);
        }
        if (!inodePath.fullPathExists()) {
          throw new FileDoesNotExistException(ExceptionMessage.PATH_DOES_NOT_EXIST
              .getMessage(path));
        }

        List<String> failedChildren = new ArrayList<>();
        if (context.getOptions().getRecursive()) {
          List<MountInfo> childrenMountPoints = mMountTable.findChildrenMountPoints(path, true);
          if (!childrenMountPoints.isEmpty()) {
            LOG.debug("Recursively deleting {} which contains mount points {}",
                path, childrenMountPoints);
            if (!context.getOptions().hasDeleteMountPoint()
                || !context.getOptions().getDeleteMountPoint()) {
              auditContext.setAllowed(false);
              throw new AccessControlException(String.format(
                  "Cannot delete path %s which is or contains a mount point "
                      + "without --deleteMountPoint/-m option specified", path));
            }
            for (MountInfo mount : childrenMountPoints) {
              if (mount.getOptions().getReadOnly()) {
                failedChildren.add(new AccessControlException(ExceptionMessage.MOUNT_READONLY,
                    mount.getAlluxioUri(), mount).getMessage());
              }
            }
          }
          if (failedChildren.size() > 0) {
            auditContext.setAllowed(false);
            throw new AccessControlException(
                MessageFormat.format("Cannot delete directory {0}. Failed to delete children: {1}",
                    path, StringUtils.join(failedChildren, ",")));
          }
        }

        deleteInternal(rpcContext, inodePath, context, false);
        if (context.getOptions().getAlluxioOnly()
            && context.getOptions().hasSyncParentNextTime()) {
          boolean syncParentNextTime = context.getOptions().getSyncParentNextTime();
          mInodeTree.setDirectChildrenLoaded(
              rpcContext, inodePath.getParentInodeDirectory(), !syncParentNextTime);
        }
        auditContext.setSucceeded(true);
        cacheOperation(context);
      }
    }
  }

  /**
   * Implements file deletion.
   * <p>
   * This method does not delete blocks. Instead, it returns deleted inodes so that their blocks can
   * be deleted after the inode deletion journal entry has been written. We cannot delete blocks
   * earlier because the inode deletion may fail, leaving us with inode containing deleted blocks.
   *
   * This method is used at:
   * (1) delete()
   * (2) unmount()
   * (3) metadata sync (when a file/dir has been removed in UFS)
   * Permission check should be skipped in (2) and (3).
   *
   * @param rpcContext the rpc context
   * @param inodePath the file {@link LockedInodePath}
   * @param deleteContext the method optitions
   * @param bypassPermCheck whether the permission check has been done before entering this call
   * @return the number of inodes deleted, and the number of inodes skipped that were unable
   * to be deleted
   */
  @VisibleForTesting
  public Pair<Integer, Integer> deleteInternal(RpcContext rpcContext, LockedInodePath inodePath,
      DeleteContext deleteContext, boolean bypassPermCheck) throws FileDoesNotExistException,
      IOException, DirectoryNotEmptyException, InvalidPathException {
    Preconditions.checkState(inodePath.getLockPattern() == LockPattern.WRITE_EDGE);

    // TODO(jiri): A crash after any UFS object is deleted and before the delete operation is
    // journaled will result in an inconsistency between Alluxio and UFS.
    if (!inodePath.fullPathExists()) {
      return new Pair<>(0, 0);
    }
    long opTimeMs = mClock.millis();
    Inode inode = inodePath.getInode();
    if (inode == null) {
      return new Pair<>(0, 0);
    }

    if (deleteContext.isSkipNotPersisted() && inode.isFile()) {
      InodeFile inodeFile = inode.asFile();
      // skip deleting a non persisted file
      if (!inodeFile.isPersisted() || !inodeFile.isCompleted()) {
        return new Pair<>(0, 1);
      }
    }
    boolean recursive = deleteContext.getOptions().getRecursive();
    if (inode.isDirectory() && !recursive && mInodeStore.hasChildren(inode.asDirectory())) {
      // inode is nonempty, and we don't want to delete a nonempty directory unless recursive is
      // true
      throw new DirectoryNotEmptyException(ExceptionMessage.DELETE_NONEMPTY_DIRECTORY_NONRECURSIVE,
          inode.getName());
    }
    if (mInodeTree.isRootId(inode.getId())) {
      // The root cannot be deleted.
      throw new InvalidPathException(ExceptionMessage.DELETE_ROOT_DIRECTORY.getMessage());
    }

    // Inodes for which deletion will be attempted
    List<Pair<AlluxioURI, LockedInodePath>> inodesToDelete;
    if (inode.isDirectory()) {
      inodesToDelete = new ArrayList<>((int) inode.asDirectory().getChildCount());
    } else {
      inodesToDelete = new ArrayList<>(1);
    }
    // Add root of sub-tree to delete
    inodesToDelete.add(new Pair<>(inodePath.getUri(), inodePath));
    // Inodes that are not safe for recursive deletes
    // Issues#15266: This can be replaced by a Trie<Long> using prefix matching
    Set<Long> unsafeInodes = new HashSet<>();
    // Unsafe parents due to containing a child which cannot be deleted
    // are initially contained in a separate set, allowing their children
    // to be deleted for which the user has permissions
    Set<Long> unsafeParentInodes = new HashSet<>();
    // Alluxio URIs (and the reason for failure) which could not be deleted
    List<Pair<String, String>> failedUris = new ArrayList<>();
    int inodeToDeleteUnsafeCount = 0;

    try (LockedInodePathList descendants = mInodeTree.getDescendants(inodePath)) {
      // This walks the tree in a DFS flavor, first all the children in a subtree,
      // then the sibling trees one by one.
      // Therefore, we first see a parent, then all its children.
      for (LockedInodePath childPath : descendants) {
        // Check if we should skip non-persisted files
        if (deleteContext.isSkipNotPersisted() && childPath.getInode().isFile()) {
          InodeFile childInode = childPath.getInode().asFile();
          if (!childInode.isCompleted() || !childInode.isPersisted()) {
            unsafeInodes.add(childInode.getId());
            unsafeParentInodes.add(childInode.getParentId());
            continue;
          }
        }
        if (bypassPermCheck) {
          inodesToDelete.add(new Pair<>(mInodeTree.getPath(childPath.getInode()), childPath));
        } else {
          try {
            // If this inode's parent already failed the permission check, skip this child
            // Because we first see the parent then all its children
            if (unsafeInodes.contains(childPath.getAncestorInode().getId())) {
              // We still need to add this child to the unsafe set because we are going to
              // walk over this child's children.
              unsafeInodes.add(childPath.getInode().getId());
              continue;
            }
            mPermissionChecker.checkPermission(Mode.Bits.WRITE, childPath);
            inodesToDelete.add(new Pair<>(mInodeTree.getPath(childPath.getInode()), childPath));
          } catch (AccessControlException e) {
            // If we do not have permission to delete the inode, then add to unsafe set
            Inode inodeToDelete = childPath.getInode();
            unsafeInodes.add(inodeToDelete.getId());
            // Propagate 'unsafe-ness' to parent as one of its descendants can't be deleted
            unsafeParentInodes.add(inodeToDelete.getParentId());
            // All this node's children will be skipped in the failure message
            failedUris.add(new Pair<>(childPath.toString(), e.getMessage()));
          }
        }
      }
      unsafeInodes.addAll(unsafeParentInodes);
      // Prepare to delete persisted inodes
      UfsDeleter ufsDeleter = NoopUfsDeleter.INSTANCE;
      if (!deleteContext.getOptions().getAlluxioOnly()) {
        ufsDeleter = new SafeUfsDeleter(mMountTable, mInodeStore,
            inodesToDelete, deleteContext.getOptions().build());
      }

      // We go through each inode, removing it from its parent set and from mDelInodes. If it's a
      // file, we deal with the checkpoints and blocks as well.
      for (int i = inodesToDelete.size() - 1; i >= 0; i--) {
        rpcContext.throwIfCancelled();
        Pair<AlluxioURI, LockedInodePath> inodePairToDelete = inodesToDelete.get(i);
        AlluxioURI alluxioUriToDelete = inodePairToDelete.getFirst();
        Inode inodeToDelete = inodePairToDelete.getSecond().getInode();

        String failureReason = null;
        if (unsafeInodes.contains(inodeToDelete.getId())) {
          failureReason = "Directory not empty";
        } else if (inodeToDelete.isPersisted()) {
          // If this is a mount point, we have deleted all the children and can unmount it
          // TODO(calvin): Add tests (ALLUXIO-1831)
          if (mMountTable.isMountPoint(alluxioUriToDelete)) {
            mMountTable.delete(rpcContext, alluxioUriToDelete, true);
          } else {
            if (!deleteContext.getOptions().getAlluxioOnly()) {
              try {
                checkUfsMode(alluxioUriToDelete, OperationType.WRITE);
                // Attempt to delete node if all children were deleted successfully
                ufsDeleter.delete(alluxioUriToDelete, inodeToDelete);
              } catch (AccessControlException | IOException e) {
                // In case ufs is not writable, we will still attempt to delete other entries
                // if any as they may be from a different mount point
                LOG.warn("Failed to delete {}: {}", alluxioUriToDelete, e.toString());
                failureReason = e.getMessage();
              }
            }
          }
        }
        if (failureReason == null) {
          if (inodeToDelete.isFile()) {
            long fileId = inodeToDelete.getId();
            // Remove the file from the set of files to persist.
            mPersistRequests.remove(fileId);
            // Cancel any ongoing jobs.
            PersistJob job = mPersistJobs.get(fileId);
            if (job != null) {
              job.setCancelState(PersistJob.CancelState.TO_BE_CANCELED);
            }
          }
        } else {
          unsafeInodes.add(inodeToDelete.getId());
          // Propagate 'unsafe-ness' to parent as one of its descendants can't be deleted
          unsafeInodes.add(inodeToDelete.getParentId());
          failedUris.add(new Pair<>(alluxioUriToDelete.toString(), failureReason));

          // Something went wrong with this path so it cannot be removed normally
          // Remove the path from further processing
          inodesToDelete.set(i, null);
          inodeToDeleteUnsafeCount++;
        }
      }

      if (mSyncManager.isSyncPoint(inodePath.getUri())) {
        mSyncManager.stopSyncAndJournal(rpcContext, inodePath.getUri());
      }

      // Delete Inodes from children to parents
      int journalFlushCounter = 0;
      for (int i = inodesToDelete.size() - 1; i >= 0; i--) {
        Pair<AlluxioURI, LockedInodePath> delInodePair = inodesToDelete.get(i);
        // The entry is null because an error is met from the pre-processing
        if (delInodePair == null) {
          continue;
        }
        LockedInodePath tempInodePath = delInodePair.getSecond();
        MountTable.Resolution resolution = mMountTable.resolve(tempInodePath.getUri());
        mInodeTree.deleteInode(rpcContext, tempInodePath, opTimeMs);
        if (deleteContext.getOptions().getAlluxioOnly()) {
          Metrics.getUfsOpsSavedCounter(resolution.getUfsMountPointUri(),
              Metrics.UFSOps.DELETE_FILE).inc();
        }
        journalFlushCounter++;
        if (mMergeInodeJournals
            && journalFlushCounter > mRecursiveOperationForceFlushEntries) {
          rpcContext.getJournalContext().flush();
          journalFlushCounter = 0;
        }
      }

      if (!failedUris.isEmpty() && !deleteContext.isSkipNotPersisted()) {
        throw new FailedPreconditionException(buildDeleteFailureMessage(failedUris));
      }
    }
    Metrics.PATHS_DELETED.inc(inodesToDelete.size());
    int inodeSkipped = unsafeInodes.size();
    if (!unsafeInodes.isEmpty()) {
      // remove 1 because we added the parent of the path being deleted
      inodeSkipped--;
    }
    return new Pair<>(inodesToDelete.size() - inodeToDeleteUnsafeCount, inodeSkipped);
  }

  private String buildDeleteFailureMessage(List<Pair<String, String>> failedUris) {
    // DELETE_FAILED_UFS("Failed to delete {0} from the under file system"),
    StringBuilder errorReport = new StringBuilder(MessageFormat
        .format("Failed to delete {0} from the under file system", failedUris.size() + " paths"));
    boolean trim = !LOG.isDebugEnabled() && failedUris.size() > 20;
    errorReport.append(": ");
    for (int i = 0; i < (trim ? 20 : failedUris.size()); i++) {
      if (i > 0) {
        errorReport.append(", ");
      }
      Pair<String, String> pathAndError = failedUris.get(i);
      errorReport.append(String.format("%s (%s)",
          pathAndError.getFirst(), pathAndError.getSecond()));
    }
    if (trim) {
      errorReport.append("...(only 20 errors shown)");
    }
    return errorReport.toString();
  }

  @Override
  public List<FileBlockInfo> getFileBlockInfoList(AlluxioURI path)
      throws FileDoesNotExistException, InvalidPathException, AccessControlException,
      UnavailableException {
    Metrics.GET_FILE_BLOCK_INFO_OPS.inc();
    try (LockedInodePath inodePath = mInodeTree.lockFullInodePath(
        path, LockPattern.READ, NoopJournalContext.INSTANCE
    );
         FileSystemMasterAuditContext auditContext =
            createAuditContext("getFileBlockInfoList", path, null, inodePath.getInodeOrNull())) {
      try {
        mPermissionChecker.checkPermission(Mode.Bits.READ, inodePath);
      } catch (AccessControlException e) {
        auditContext.setAllowed(false);
        throw e;
      }
      List<FileBlockInfo> ret = getFileBlockInfoListInternal(inodePath, false);
      Metrics.FILE_BLOCK_INFOS_GOT.inc();
      auditContext.setSucceeded(true);
      return ret;
    }
  }

  /**
   * @param inodePath the {@link LockedInodePath} to get the info for
   * @param excludeMountInfo exclude the mount info
   * @return a list of {@link FileBlockInfo} for all the blocks of the given inode
   */
  private List<FileBlockInfo> getFileBlockInfoListInternal(LockedInodePath inodePath,
      boolean excludeMountInfo)
      throws InvalidPathException, FileDoesNotExistException, UnavailableException {
    InodeFile file = inodePath.getInodeFile();
    List<BlockInfo> blockInfoList = mBlockMaster.getBlockInfoList(file.getBlockIds());

    List<FileBlockInfo> ret = new ArrayList<>(blockInfoList.size());
    for (BlockInfo blockInfo : blockInfoList) {
      ret.add(generateFileBlockInfo(inodePath, blockInfo, excludeMountInfo));
    }
    return ret;
  }

  /**
   * Generates a {@link FileBlockInfo} object from internal metadata. This adds file information to
   * the block, such as the file offset, and additional UFS locations for the block.
   *
   * @param inodePath the file the block is a part of
   * @param blockInfo the {@link BlockInfo} to generate the {@link FileBlockInfo} from
   * @param excludeMountInfo exclude the mount info
   * @return a new {@link FileBlockInfo} for the block
   */
  private FileBlockInfo generateFileBlockInfo(LockedInodePath inodePath, BlockInfo blockInfo,
      boolean excludeMountInfo)
      throws FileDoesNotExistException {
    InodeFile file = inodePath.getInodeFile();
    FileBlockInfo fileBlockInfo = new FileBlockInfo();
    fileBlockInfo.setBlockInfo(blockInfo);
    fileBlockInfo.setUfsLocations(new ArrayList<>());

    // The sequence number part of the block id is the block index.
    long offset = file.getBlockSizeBytes() * BlockId.getSequenceNumber(blockInfo.getBlockId());
    fileBlockInfo.setOffset(offset);

    if (!excludeMountInfo && fileBlockInfo.getBlockInfo().getLocations().isEmpty()
        && file.isPersisted()) {
      // No alluxio locations, but there is a checkpoint in the under storage system. Add the
      // locations from the under storage system.
      long blockId = fileBlockInfo.getBlockInfo().getBlockId();
      List<String> locations = mUfsBlockLocationCache.get(blockId, inodePath.getUri(),
          fileBlockInfo.getOffset());
      if (locations != null) {
        fileBlockInfo.setUfsLocations(locations);
      }
    }
    return fileBlockInfo;
  }

  /**
   * Returns whether the inodeFile is fully in Alluxio or not. The file is fully in Alluxio only if
   * all the blocks of the file are in Alluxio, in other words, the in-Alluxio percentage is 100.
   *
   * @return true if the file is fully in Alluxio, false otherwise
   */
  private boolean isFullyInAlluxio(InodeFile inode) throws UnavailableException {
    return getInAlluxioPercentage(inode) == 100;
  }

  /**
   * Returns whether the inodeFile is fully in memory or not. The file is fully in memory only if
   * all the blocks of the file are in memory, in other words, the in-memory percentage is 100.
   *
   * @return true if the file is fully in Alluxio, false otherwise
   */
  private boolean isFullyInMemory(InodeFile inode) throws UnavailableException {
    return getInMemoryPercentage(inode) == 100;
  }

  @Override
  public List<AlluxioURI> getInAlluxioFiles() throws UnavailableException {
    List<AlluxioURI> files = new ArrayList<>();
    LockedInodePath rootPath;
    try {
      rootPath =
          mInodeTree.lockFullInodePath(
              new AlluxioURI(AlluxioURI.SEPARATOR),
              LockPattern.READ,
              NoopJournalContext.INSTANCE);
    } catch (FileDoesNotExistException | InvalidPathException e) {
      // Root should always exist.
      throw new RuntimeException(e);
    }

    try (LockedInodePath inodePath = rootPath) {
      getInAlluxioFilesInternal(inodePath, files,
          Configuration.getInt(PropertyKey.MASTER_WEB_IN_ALLUXIO_DATA_PAGE_COUNT));
    }
    return files;
  }

  @Override
  public List<AlluxioURI> getInMemoryFiles() throws UnavailableException {
    List<AlluxioURI> files = new ArrayList<>();
    LockedInodePath rootPath;
    try {
      rootPath =
          mInodeTree.lockFullInodePath(
              new AlluxioURI(AlluxioURI.SEPARATOR),
              LockPattern.READ,
              NoopJournalContext.INSTANCE);
    } catch (FileDoesNotExistException | InvalidPathException e) {
      // Root should always exist.
      throw new RuntimeException(e);
    }

    try (LockedInodePath inodePath = rootPath) {
      getInMemoryFilesInternal(inodePath, files);
    }
    return files;
  }

  /**
   * Adds in-Alluxio files to the array list passed in. This method assumes the inode passed in is
   * already read locked.
   *
   * @param inodePath the inode path to search
   * @param files the list to accumulate the results in
   */
  private void getInAlluxioFilesInternal(LockedInodePath inodePath, List<AlluxioURI> files,
      int fileCount) throws UnavailableException {
    Inode inode = inodePath.getInodeOrNull();
    if (inode == null || files.size() >= fileCount) {
      return;
    }

    if (inode.isFile()) {
      if (isFullyInAlluxio(inode.asFile())) {
        files.add(inodePath.getUri());
      }
    } else {
      // This inode is a directory.
      try (CloseableIterator<? extends Inode> it = mInodeStore.getChildren(inode.asDirectory())) {
        while (it.hasNext()) {
          Inode child = it.next();
          try (LockedInodePath childPath = inodePath.lockChild(child, LockPattern.READ)) {
            getInAlluxioFilesInternal(childPath, files, fileCount);
          } catch (InvalidPathException e) {
            // Inode is no longer a child, continue.
          }
        }
      }
    }
  }

  /**
   * Adds in-memory files to the array list passed in. This method assumes the inode passed in is
   * already read locked.
   *
   * @param inodePath the inode path to search
   * @param files the list to accumulate the results in
   */
  private void getInMemoryFilesInternal(LockedInodePath inodePath, List<AlluxioURI> files)
      throws UnavailableException {
    Inode inode = inodePath.getInodeOrNull();
    if (inode == null) {
      return;
    }

    if (inode.isFile()) {
      if (isFullyInMemory(inode.asFile())) {
        files.add(inodePath.getUri());
      }
    } else {
      // This inode is a directory.
      try (CloseableIterator<? extends Inode> it = mInodeStore.getChildren(inode.asDirectory())) {
        while (it.hasNext()) {
          Inode child = it.next();
          try (LockedInodePath childPath = inodePath.lockChild(child, LockPattern.READ)) {
            getInMemoryFilesInternal(childPath, files);
          } catch (InvalidPathException e) {
            // Inode is no longer a child, continue.
          }
        }
      }
    }
  }

  /**
   * Gets the in-memory percentage of an Inode. For a file that has all blocks in Alluxio, it
   * returns 100; for a file that has no block in memory, it returns 0. Returns 0 for a directory.
   *
   * @param inode the inode
   * @return the in memory percentage
   */
  private int getInMemoryPercentage(Inode inode) throws UnavailableException {
    if (!inode.isFile()) {
      return 0;
    }
    InodeFile inodeFile = inode.asFile();

    return getFileInMemoryPercentageInternal(inodeFile,
        mBlockMaster.getBlockInfoList(inodeFile.getBlockIds()));
  }

  /**
   * Gets the File in-memory percentage of a File Inode.
   *
   * @param blockInfos the inode
   * @return the in memory percentage
   */
  private int getFileInMemoryPercentageInternal(InodeFile inodeFile, List<BlockInfo> blockInfos) {
    long length = inodeFile.getLength();
    if (length == 0) {
      return 100;
    }

    long inMemoryLength = 0;
    for (BlockInfo info : blockInfos) {
      if (isInTopStorageTier(info)) {
        inMemoryLength += info.getLength();
      }
    }
    return (int) (inMemoryLength * 100 / length);
  }

  /**
   * Gets the in-Alluxio percentage of an Inode. For a file that has all blocks in Alluxio, it
   * returns 100; for a file that has no block in Alluxio, it returns 0. Returns 0 for a directory.
   *
   * @param inode the inode
   * @return the in alluxio percentage
   */
  private int getInAlluxioPercentage(Inode inode) throws UnavailableException {
    if (!inode.isFile()) {
      return 0;
    }
    InodeFile inodeFile = inode.asFile();

    return getFileInAlluxioPercentageInternal(inodeFile,
        mBlockMaster.getBlockInfoList(inodeFile.getBlockIds()));
  }

  /**
   * Gets the File in-Alluxio percentage of a File Inode.
   *
   * @param inodeFile the File inode
   * @return the in alluxio percentage
   */
  private int getFileInAlluxioPercentageInternal(InodeFile inodeFile, List<BlockInfo> blockInfos) {
    long length = inodeFile.getLength();
    if (length == 0) {
      return 100;
    }

    long inAlluxioLength = 0;
    for (BlockInfo info : blockInfos) {
      if (!info.getLocations().isEmpty()) {
        inAlluxioLength += info.getLength();
      }
    }
    return (int) (inAlluxioLength * 100 / length);
  }

  /**
   * @return true if the given block is in the top storage level in some worker, false otherwise
   */
  private boolean isInTopStorageTier(BlockInfo blockInfo) {
    for (BlockLocation location : blockInfo.getLocations()) {
      if (mBlockMaster.getGlobalStorageTierAssoc().getOrdinal(location.getTierAlias()) == 0) {
        return true;
      }
    }
    return false;
  }

  @Override
  public long createDirectory(AlluxioURI path, CreateDirectoryContext context)
      throws InvalidPathException, FileAlreadyExistsException, IOException, AccessControlException,
      FileDoesNotExistException {
    if (isOperationComplete(context)) {
      Metrics.COMPLETED_OPERATION_RETRIED_COUNT.inc();
      LOG.warn("A completed \"createDirectory\" operation has been retried. OperationContext={}",
          context);
      return getFileInfo(path,
          GetStatusContext.create(GetStatusPOptions.newBuilder()
              .setCommonOptions(FileSystemMasterCommonPOptions.newBuilder().setSyncIntervalMs(-1))
              .setLoadMetadataType(LoadMetadataPType.NEVER).setUpdateTimestamps(false)))
                  .getFileId();
    }
    Metrics.CREATE_DIRECTORIES_OPS.inc();
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("mkdir", path, null, null)) {

      syncMetadata(rpcContext,
          path,
          context.getOptions().getCommonOptions(),
          DescendantType.ONE,
          auditContext,
          inodePath -> context.getOptions().getRecursive()
              ? inodePath.getLastExistingInode() : inodePath.getParentInodeOrNull()
      );

      LockingScheme lockingScheme =
          createLockingScheme(path, context.getOptions().getCommonOptions(),
              LockPattern.WRITE_EDGE);
      try (LockedInodePath inodePath =
               mInodeTree.lockInodePath(lockingScheme, rpcContext.getJournalContext())
      ) {
        auditContext.setSrcInode(inodePath.getParentInodeOrNull());
        if (context.getOptions().getRecursive()) {
          auditContext.setSrcInode(inodePath.getLastExistingInode());
        }
        try {
          mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, inodePath);
        } catch (AccessControlException e) {
          auditContext.setAllowed(false);
          throw e;
        }

        mMountTable.checkUnderWritableMountPoint(path);
        if (context.isPersisted()) {
          checkUfsMode(path, OperationType.WRITE);
        }
        createDirectoryInternal(rpcContext, inodePath, context);
        auditContext.setSrcInode(inodePath.getInode()).setSucceeded(true);
        cacheOperation(context);
        return inodePath.getInode().getId();
      }
    }
  }

  /**
   * Implementation of the directory creation. Before creating by this function, the
   * corresponding UFS client and UFS uri of the inodePath have to be prepared.
   *
   * @param rpcContext the rpc context
   * @param inodePath the path of the directory
   * @param ufsClient the corresponding ufsClient of the given inodePath
   * @param ufsUri the corresponding ufsPath of the given inodePath
   * @param context method context
   * @return a list of created inodes
   */
  public List<Inode> createDirectoryInternal(RpcContext rpcContext, LockedInodePath inodePath,
      UfsManager.UfsClient ufsClient, AlluxioURI ufsUri, CreateDirectoryContext context) throws
      InvalidPathException, FileAlreadyExistsException, IOException, FileDoesNotExistException {
    Preconditions.checkState(inodePath.getLockPattern() == LockPattern.WRITE_EDGE);

    try {
      List<Inode> createResult = mInodeTree.createPath(rpcContext, inodePath, context);
      InodeDirectory inodeDirectory = inodePath.getInode().asDirectory();

      String ufsFingerprint = Constants.INVALID_UFS_FINGERPRINT;
      if (inodeDirectory.isPersisted()) {
        UfsStatus ufsStatus = context.getUfsStatus();
        // Retrieve the UFS fingerprint for this file.
        try (CloseableResource<UnderFileSystem> ufsResource = ufsClient.acquireUfsResource()) {
          UnderFileSystem ufs = ufsResource.get();
          if (ufsStatus == null) {
            ufsFingerprint = ufs.getParsedFingerprint(ufsUri.toString()).serialize();
          } else {
            ufsFingerprint = Fingerprint.create(ufs.getUnderFSType(), ufsStatus).serialize();
          }
        }
      }

      mInodeTree.updateInode(rpcContext, UpdateInodeEntry.newBuilder()
          .setId(inodeDirectory.getId())
          .setUfsFingerprint(ufsFingerprint)
          .build());

      if (context.isPersisted()) {
        // The path exists in UFS, so it is no longer absent.
        mUfsAbsentPathCache.processExisting(inodePath.getUri());
      }

      Metrics.DIRECTORIES_CREATED.inc();
      return createResult;
    } catch (BlockInfoException e) {
      // Since we are creating a directory, the block size is ignored, no such exception should
      // happen.
      throw new RuntimeException(e);
    }
  }

  /**
   * Implementation of directory creation for a given path.
   *
   * @param rpcContext the rpc context
   * @param inodePath the path of the directory
   * @param context method context
   * @return a list of created inodes
   */
  List<Inode> createDirectoryInternal(RpcContext rpcContext, LockedInodePath inodePath,
      CreateDirectoryContext context) throws InvalidPathException, FileAlreadyExistsException,
      IOException, FileDoesNotExistException {
    Preconditions.checkState(inodePath.getLockPattern() == LockPattern.WRITE_EDGE);
    MountTable.Resolution resolution = mMountTable.resolve(inodePath.getUri());

    return createDirectoryInternal(rpcContext, inodePath, resolution.getUfsClient(),
        resolution.getUri(), context);
  }

  @Override
  public void rename(AlluxioURI srcPath, AlluxioURI dstPath, RenameContext context)
      throws FileAlreadyExistsException, FileDoesNotExistException, InvalidPathException,
      IOException, AccessControlException {
    if (isOperationComplete(context)) {
      Metrics.COMPLETED_OPERATION_RETRIED_COUNT.inc();
      LOG.warn("A completed \"rename\" operation has been retried. OperationContext={}", context);
      return;
    }
    Metrics.RENAME_PATH_OPS.inc();
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("rename", srcPath, dstPath, null)) {

      syncMetadata(rpcContext,
          srcPath,
          context.getOptions().getCommonOptions(),
          DescendantType.ONE,
          auditContext,
          LockedInodePath::getParentInodeOrNull
      );

      syncMetadata(rpcContext,
          dstPath,
          context.getOptions().getCommonOptions(),
          DescendantType.ONE,
          auditContext,
          LockedInodePath::getParentInodeOrNull
      );

      LockingScheme srcLockingScheme =
          createLockingScheme(srcPath, context.getOptions().getCommonOptions(),
              LockPattern.WRITE_EDGE);
      LockingScheme dstLockingScheme =
          createLockingScheme(dstPath, context.getOptions().getCommonOptions(),
              LockPattern.WRITE_EDGE);
      try (InodePathPair inodePathPair = mInodeTree
              .lockInodePathPair(srcLockingScheme.getPath(), srcLockingScheme.getPattern(),
                  dstLockingScheme.getPath(), dstLockingScheme.getPattern(),
                  rpcContext.getJournalContext())
      ) {
        LockedInodePath srcInodePath = inodePathPair.getFirst();
        LockedInodePath dstInodePath = inodePathPair.getSecond();
        auditContext.setSrcInode(srcInodePath.getParentInodeOrNull());
        try {
          mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, srcInodePath);
          mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, dstInodePath);
        } catch (AccessControlException e) {
          auditContext.setAllowed(false);
          throw e;
        }

        mMountTable.checkUnderWritableMountPoint(srcPath);
        mMountTable.checkUnderWritableMountPoint(dstPath);
        renameInternal(rpcContext, srcInodePath, dstInodePath, context);
        auditContext.setSrcInode(srcInodePath.getInode()).setSucceeded(true);
        cacheOperation(context);
        LOG.debug("Renamed {} to {}", srcPath, dstPath);
      }
    }
  }

  private boolean shouldPersistPath(String path) {
    for (String pattern : mPersistBlacklist) {
      if (path.contains(pattern)) {
        LOG.debug("Not persisting path {} because it is in {}: {}", path,
            PropertyKey.Name.MASTER_PERSISTENCE_BLACKLIST, mPersistBlacklist);
        return false;
      }
    }
    return true;
  }

  /**
   * Renames a file to a destination.
   *
   * @param rpcContext the rpc context
   * @param srcInodePath the source path to rename
   * @param dstInodePath the destination path to rename the file to
   * @param context method options
   */
  private void renameInternal(RpcContext rpcContext, LockedInodePath srcInodePath,
      LockedInodePath dstInodePath, RenameContext context) throws InvalidPathException,
          FileDoesNotExistException, FileAlreadyExistsException,
          IOException, AccessControlException {
    if (!srcInodePath.fullPathExists()) {
      throw new FileDoesNotExistException(
          ExceptionMessage.PATH_DOES_NOT_EXIST.getMessage(srcInodePath.getUri()));
    }

    Inode srcInode = srcInodePath.getInode();
    // Renaming path to itself is a no-op.
    if (srcInodePath.getUri().equals(dstInodePath.getUri())) {
      return;
    }
    // Renaming the root is not allowed.
    if (srcInodePath.getUri().isRoot()) {
      throw new InvalidPathException(ExceptionMessage.ROOT_CANNOT_BE_RENAMED.getMessage());
    }
    if (dstInodePath.getUri().isRoot()) {
      throw new InvalidPathException(ExceptionMessage.RENAME_CANNOT_BE_TO_ROOT.getMessage());
    }
    // Renaming across mount points is not allowed.
    String srcMount = mMountTable.getMountPoint(srcInodePath.getUri());
    String dstMount = mMountTable.getMountPoint(dstInodePath.getUri());
    if ((srcMount == null && dstMount != null) || (srcMount != null && dstMount == null)
        || (srcMount != null && dstMount != null && !srcMount.equals(dstMount))) {
      throw new InvalidPathException(
          MessageFormat.format("Renaming {0} to {1} is a cross mount operation",
              srcInodePath.getUri(), dstInodePath.getUri()));
    }
    // Renaming onto a mount point is not allowed.
    if (mMountTable.isMountPoint(dstInodePath.getUri())) {
      throw new InvalidPathException(MessageFormat
          .format("{0} is a mount point and cannot be renamed onto", dstInodePath.getUri()));
    }
    // Renaming a path to one of its subpaths is not allowed. Check for that, by making sure
    // srcComponents isn't a prefix of dstComponents.
    if (PathUtils.hasPrefix(dstInodePath.getUri().getPath(), srcInodePath.getUri().getPath())) {
      throw new InvalidPathException(
          MessageFormat.format("Cannot rename because {0} is a prefix of {1}",
              srcInodePath.getUri(), dstInodePath.getUri()));
    }

    // Get the inodes of the src and dst parents.
    Inode srcParentInode = srcInodePath.getParentInodeDirectory();
    if (!srcParentInode.isDirectory()) {
      throw new InvalidPathException(
          ExceptionMessage.PATH_MUST_HAVE_VALID_PARENT.getMessage(srcInodePath.getUri()));
    }
    Inode dstParentInode = dstInodePath.getParentInodeDirectory();
    if (!dstParentInode.isDirectory()) {
      throw new InvalidPathException(
          ExceptionMessage.PATH_MUST_HAVE_VALID_PARENT.getMessage(dstInodePath.getUri()));
    }

    // Make sure destination path does not exist, or check if there's overwrite syntax involved
    if (dstInodePath.fullPathExists()) {
      boolean proceed = checkForOverwriteSyntax(rpcContext, srcInodePath, dstInodePath, context);
      if (!proceed) {
        return;
      }
    }

    // Now we remove srcInode from its parent and insert it into dstPath's parent
    renameInternal(rpcContext, srcInodePath, dstInodePath, false, context);

    // Check options and determine if we should schedule async persist. This is helpful for compute
    // frameworks that use rename as a commit operation.
    if (context.getPersist() && srcInode.isFile() && !srcInode.isPersisted()
        && shouldPersistPath(dstInodePath.toString())) {
      LOG.debug("Schedule Async Persist on rename for File {}", srcInodePath);
      mInodeTree.updateInode(rpcContext, UpdateInodeEntry.newBuilder()
          .setId(srcInode.getId())
          .setPersistenceState(PersistenceState.TO_BE_PERSISTED.name())
          .build());
      long shouldPersistTime = srcInode.asFile().getShouldPersistTime();
      long persistenceWaitTime = shouldPersistTime == Constants.NO_AUTO_PERSIST ? 0
          : getPersistenceWaitTime(shouldPersistTime);
      mPersistRequests.put(srcInode.getId(), new alluxio.time.ExponentialTimer(
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_INITIAL_INTERVAL_MS),
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_INTERVAL_MS),
          persistenceWaitTime,
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_TOTAL_WAIT_TIME_MS)));
    }

    // If a directory is being renamed with persist on rename, attempt to persist children
    int journalFlushCounter = 0;
    if (srcInode.isDirectory() && context.getPersist()
        && shouldPersistPath(dstInodePath.toString())) {
      LOG.debug("Schedule Async Persist on rename for Dir: {}", dstInodePath);
      try (LockedInodePathList descendants = mInodeTree.getDescendants(srcInodePath)) {
        for (LockedInodePath childPath : descendants) {
          Inode childInode = childPath.getInode();
          // TODO(apc999): Resolve the child path legitimately
          if (childInode.isFile() && !childInode.isPersisted()
              && shouldPersistPath(
                  childPath.toString().substring(srcInodePath.toString().length()))) {
            LOG.debug("Schedule Async Persist on rename for Child File: {}", childPath);
            mInodeTree.updateInode(rpcContext, UpdateInodeEntry.newBuilder()
                .setId(childInode.getId())
                .setPersistenceState(PersistenceState.TO_BE_PERSISTED.name())
                .build());
            long shouldPersistTime = childInode.asFile().getShouldPersistTime();
            long persistenceWaitTime = shouldPersistTime == Constants.NO_AUTO_PERSIST ? 0
                : getPersistenceWaitTime(shouldPersistTime);
            mPersistRequests.put(childInode.getId(), new alluxio.time.ExponentialTimer(
                Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_INITIAL_INTERVAL_MS),
                Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_INTERVAL_MS),
                persistenceWaitTime,
                Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_TOTAL_WAIT_TIME_MS)));
            journalFlushCounter++;
            if (mMergeInodeJournals
                && journalFlushCounter > mRecursiveOperationForceFlushEntries) {
              rpcContext.getJournalContext().flush();
              journalFlushCounter = 0;
            }
          }
        }
      }
    }
  }

  /**
   * Implements renaming.
   *
   * @param rpcContext the rpc context
   * @param srcInodePath the path of the rename source
   * @param dstInodePath the path to the rename destination
   * @param replayed whether the operation is a result of replaying the journal
   * @param context method options
   */
  private void renameInternal(RpcContext rpcContext, LockedInodePath srcInodePath,
      LockedInodePath dstInodePath, boolean replayed, RenameContext context)
      throws FileDoesNotExistException, InvalidPathException, IOException, AccessControlException {

    // Rename logic:
    // 1. Change the source inode name to the destination name.
    // 2. Insert the source inode into the destination parent.
    // 3. Do UFS operations if necessary.
    // 4. Remove the source inode (reverting the name) from the source parent.
    // 5. Set the last modification times for both source and destination parent inodes.

    Inode srcInode = srcInodePath.getInode();
    AlluxioURI srcPath = srcInodePath.getUri();
    AlluxioURI dstPath = dstInodePath.getUri();
    InodeDirectory srcParentInode = srcInodePath.getParentInodeDirectory();
    InodeDirectory dstParentInode = dstInodePath.getParentInodeDirectory();
    String srcName = srcPath.getName();
    String dstName = dstPath.getName();

    LOG.debug("Renaming {} to {}", srcPath, dstPath);
    if (dstInodePath.fullPathExists()) {
      throw new InvalidPathException("Destination path: " + dstPath + " already exists.");
    }

    mInodeTree.rename(rpcContext, RenameEntry.newBuilder()
        .setId(srcInode.getId())
        .setOpTimeMs(context.getOperationTimeMs())
        .setNewParentId(dstParentInode.getId())
        .setNewName(dstName)
        .setPath(srcPath.getPath())
        .setNewPath(dstPath.getPath())
        .build());

    // 3. Do UFS operations if necessary.
    // If the source file is persisted, rename it in the UFS.
    try {
      if (!replayed && srcInode.isPersisted()) {
        // Check if ufs is writable
        checkUfsMode(srcPath, OperationType.WRITE);
        checkUfsMode(dstPath, OperationType.WRITE);

        MountTable.Resolution resolution = mMountTable.resolve(srcPath);
        // Persist ancestor directories from top to the bottom. We cannot use recursive create
        // parents here because the permission for the ancestors can be different.

        // inodes from the same mount point as the dst
        Stack<InodeDirectory> sameMountDirs = new Stack<>();
        List<Inode> dstInodeList = dstInodePath.getInodeList();
        for (int i = dstInodeList.size() - 1; i >= 0; i--) {
          // Since dstInodePath is guaranteed not to be a full path, all inodes in the incomplete
          // path are guaranteed to be a directory.
          InodeDirectory dir = dstInodeList.get(i).asDirectory();
          sameMountDirs.push(dir);
          if (dir.isMountPoint()) {
            break;
          }
        }
        while (!sameMountDirs.empty()) {
          InodeDirectory dir = sameMountDirs.pop();
          if (!dir.isPersisted()) {
            mInodeTree.syncPersistExistingDirectory(rpcContext, dir, false);
          }
        }

        String ufsSrcPath = resolution.getUri().toString();
        String ufsDstUri = mMountTable.resolve(dstPath).getUri().toString();
        try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
          UnderFileSystem ufs = ufsResource.get();
          boolean success;
          if (srcInode.isFile()) {
            success = ufs.renameRenamableFile(ufsSrcPath, ufsDstUri);
          } else {
            success = ufs.renameRenamableDirectory(ufsSrcPath, ufsDstUri);
          }
          if (!success) {
            throw new IOException(
                ExceptionMessage.FAILED_UFS_RENAME.getMessage(ufsSrcPath, ufsDstUri));
          }
        }
        // The destination was persisted in ufs.
        mUfsAbsentPathCache.processExisting(dstPath);
      }
    } catch (Throwable t) {
      // On failure, revert changes and throw exception.
      mInodeTree.rename(rpcContext, RenameEntry.newBuilder()
          .setId(srcInode.getId())
          .setOpTimeMs(context.getOperationTimeMs())
          .setNewName(srcName)
          .setNewParentId(srcParentInode.getId())
          .setPath(dstPath.getPath())
          .setNewPath(srcPath.getPath())
          .build());
      throw t;
    }

    Metrics.PATHS_RENAMED.inc();
  }

  /**
   * If destination path exists, check if we are using rename for overwrite syntax.
   * Such as objectstore client.
   * If not, a FileAlreadyExistsException should be thrown,
   * Else, return if the caller should proceed or not.
   * @return should the caller proceed or not
   */
  private boolean checkForOverwriteSyntax(RpcContext rpcContext,
                                          LockedInodePath srcInodePath,
                                          LockedInodePath dstInodePath,
                                          RenameContext context)
          throws FileDoesNotExistException, FileAlreadyExistsException,
          IOException, InvalidPathException {
    if (context.getOptions().hasS3SyntaxOptions()) {
      // For possible object overwrite
      if (context.getOptions().getS3SyntaxOptions().getIsMultipartUpload()) {
        String mpUploadIdDst = new String(dstInodePath.getInodeFile().getXAttr() != null
                ? dstInodePath.getInodeFile().getXAttr()
                .getOrDefault(PropertyKey.Name.S3_UPLOADS_ID_XATTR_KEY, new byte[0])
                : new byte[0]);
        String mpUploadIdSrc = new String(srcInodePath.getInodeFile().getXAttr() != null
                ? srcInodePath.getInodeFile().getXAttr()
                .getOrDefault(PropertyKey.Name.S3_UPLOADS_ID_XATTR_KEY, new byte[0])
                : new byte[0]);
        if (StringUtils.isNotEmpty(mpUploadIdSrc) && StringUtils.isNotEmpty(mpUploadIdDst)
                && StringUtils.equals(mpUploadIdSrc, mpUploadIdDst)) {
          LOG.info("Object with same upload exists, bail and claim success.");
        /* This is a rename operation as part of complete a CompleteMultipartUpload call
         and there's concurrent attempt on the same multipart upload succeeded */
          return false;
        }
      }
      /*
       TODO(lucy) same logic could apply for create normal object instead of multipart upload,
        check other object related property for a no-op rename
       */
      //we need to overwrite, delete existing destination path
      if (context.getOptions().getS3SyntaxOptions().getOverwrite()) {
        LOG.info("Encountered S3 Overwrite syntax, "
                + "deleting existing file and then start renaming.");
        try {
          deleteInternal(rpcContext, dstInodePath, DeleteContext
                  .mergeFrom(DeletePOptions.newBuilder()
                          .setRecursive(true).setAlluxioOnly(!context.getPersist())), true);
          dstInodePath.removeLastInode();
        } catch (DirectoryNotEmptyException ex) {
          // IGNORE, this will never happen
        }
        return true;
      }
    }
    throw new FileAlreadyExistsException(String
            .format("Cannot rename because destination already exists. src: %s dst: %s",
                    srcInodePath.getUri(), dstInodePath.getUri()));
  }

  /**
   * Propagates the persisted status to all parents of the given inode in the same mount partition.
   *
   * @param journalContext the journal context
   * @param inodePath the inode to start the propagation at
   */
  private void propagatePersistedInternal(Supplier<JournalContext> journalContext,
      LockedInodePath inodePath) throws FileDoesNotExistException {
    Inode inode = inodePath.getInode();

    List<Inode> inodes = inodePath.getInodeList();
    // Traverse the inodes from target inode to the root.
    Collections.reverse(inodes);
    // Skip the first, to not examine the target inode itself.
    inodes = inodes.subList(1, inodes.size());

    for (Inode ancestor : inodes) {
      // the path is already locked.
      AlluxioURI path = mInodeTree.getPath(ancestor);
      if (mMountTable.isMountPoint(path)) {
        // Stop propagating the persisted status at mount points.
        break;
      }
      if (ancestor.isPersisted()) {
        // Stop if a persisted directory is encountered.
        break;
      }
      mInodeTree.updateInode(journalContext, UpdateInodeEntry.newBuilder()
          .setId(ancestor.getId())
          .setPersistenceState(PersistenceState.PERSISTED.name())
          .build());
    }
  }

  @Override
  public void free(AlluxioURI path, FreeContext context)
      throws FileDoesNotExistException, InvalidPathException, AccessControlException,
      UnexpectedAlluxioException, IOException {
    Metrics.FREE_FILE_OPS.inc();
    // No need to syncMetadata before free.
    try (RpcContext rpcContext = createRpcContext(context);
         LockedInodePath inodePath =
             mInodeTree
                 .lockFullInodePath(path, LockPattern.WRITE_INODE, rpcContext.getJournalContext());
         FileSystemMasterAuditContext auditContext =
             createAuditContext("free", path, null, inodePath.getInodeOrNull())) {
      try {
        mPermissionChecker.checkPermission(Mode.Bits.READ, inodePath);
      } catch (AccessControlException e) {
        auditContext.setAllowed(false);
        throw e;
      }
      freeInternal(rpcContext, inodePath, context);
      auditContext.setSucceeded(true);
    }
  }

  /**
   * Implements free operation.
   *
   * @param rpcContext the rpc context
   * @param inodePath inode of the path to free
   * @param context context to free method
   */
  private void freeInternal(RpcContext rpcContext, LockedInodePath inodePath, FreeContext context)
      throws FileDoesNotExistException, UnexpectedAlluxioException,
      IOException, InvalidPathException, AccessControlException {
    Inode inode = inodePath.getInode();
    if (inode.isDirectory() && !context.getOptions().getRecursive()
        && mInodeStore.hasChildren(inode.asDirectory())) {
      // inode is nonempty, and we don't free a nonempty directory unless recursive is true
      throw new UnexpectedAlluxioException(
          ExceptionMessage.CANNOT_FREE_NON_EMPTY_DIR.getMessage(mInodeTree.getPath(inode)));
    }
    long opTimeMs = mClock.millis();
    List<Inode> freeInodes = new ArrayList<>();
    freeInodes.add(inode);
    try (LockedInodePathList descendants = mInodeTree.getDescendants(inodePath)) {
      int journalFlushCounter = 0;
      for (LockedInodePath descedant : Iterables.concat(descendants,
          Collections.singleton(inodePath))) {
        Inode freeInode = descedant.getInodeOrNull();

        if (freeInode != null && freeInode.isFile()) {
          if (freeInode.getPersistenceState() != PersistenceState.PERSISTED) {
            throw new UnexpectedAlluxioException(ExceptionMessage.CANNOT_FREE_NON_PERSISTED_FILE
                .getMessage(mInodeTree.getPath(freeInode)));
          }
          if (freeInode.isPinned()) {
            if (!context.getOptions().getForced()) {
              throw new UnexpectedAlluxioException(ExceptionMessage.CANNOT_FREE_PINNED_FILE
                  .getMessage(mInodeTree.getPath(freeInode)));
            }

            SetAttributeContext setAttributeContext = SetAttributeContext
                .mergeFrom(SetAttributePOptions.newBuilder().setRecursive(false).setPinned(false));
            setAttributeSingleFile(rpcContext, descedant, true, opTimeMs, setAttributeContext);
          }
          // Remove corresponding blocks from workers.
          mBlockMaster.removeBlocks(freeInode.asFile().getBlockIds(), false /* delete */);
          journalFlushCounter++;
          if (mMergeInodeJournals
              && journalFlushCounter > mRecursiveOperationForceFlushEntries) {
            rpcContext.getJournalContext().flush();
            journalFlushCounter = 0;
          }
        }
      }
    }

    Metrics.FILES_FREED.inc(freeInodes.size());
  }

  @Override
  public AlluxioURI getPath(long fileId) throws FileDoesNotExistException {
    try (LockedInodePath inodePath =
             mInodeTree.lockFullInodePath(fileId, LockPattern.READ, NoopJournalContext.INSTANCE)
    ) {
      // the path is already locked.
      return mInodeTree.getPath(inodePath.getInode());
    }
  }

  @Override
  public Set<Long> getPinIdList() {
    // return both the explicitly pinned inodes and not persisted inodes which should not be evicted
    return Sets.union(mInodeTree.getPinIdSet(), mInodeTree.getToBePersistedIds());
  }

  @Override
  public String getUfsAddress() {
    return Configuration.getString(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS);
  }

  @Override
  public UfsInfo getUfsInfo(long mountId) {
    MountInfo info = mMountTable.getMountInfo(mountId);
    if (info == null) {
      return new UfsInfo();
    }
    MountPOptions options = info.getOptions();
    return new UfsInfo().setUri(info.getUfsUri())
        .setMountOptions(MountContext
            .mergeFrom(MountPOptions.newBuilder().putAllProperties(options.getPropertiesMap())
                .setReadOnly(options.getReadOnly()).setShared(options.getShared()))
            .getOptions().build());
  }

  @Override
  public List<String> getWhiteList() {
    return mWhitelist.getList();
  }

  @Override
  public Set<Long> getLostFiles() {
    Set<Long> lostFiles = new HashSet<>();
    Iterator<Long> iter = mBlockMaster.getLostBlocksIterator();
    while (iter.hasNext()) {
      long blockId = iter.next();
      // the file id is the container id of the block id
      long containerId = BlockId.getContainerId(blockId);
      long fileId = IdUtils.createFileId(containerId);
      lostFiles.add(fileId);
    }
    return lostFiles;
  }

  /**
   * Loads metadata for the path if it is (non-existing || load direct children is set).
   *
   * See {@link #shouldLoadMetadataIfNotExists(LockedInodePath, LoadMetadataContext)}.
   *
   * @param rpcContext the rpc context
   * @param path the path to load metadata for
   * @param context the {@link LoadMetadataContext}
   */
  protected void loadMetadataIfNotExist(RpcContext rpcContext, AlluxioURI path,
      LoadMetadataContext context)
      throws InvalidPathException, AccessControlException {
    DescendantType syncDescendantType =
        GrpcUtils.fromProto(context.getOptions().getLoadDescendantType());
    FileSystemMasterCommonPOptions commonOptions =
        context.getOptions().getCommonOptions();
    boolean loadAlways = context.getOptions().hasLoadType()
        && (context.getOptions().getLoadType().equals(LoadMetadataPType.ALWAYS));
    // load metadata only and force sync
    InodeSyncStream sync = new InodeSyncStream(new LockingScheme(path, LockPattern.READ,
        commonOptions, getSyncPathCache(), syncDescendantType),
        this, getSyncPathCache(), rpcContext, syncDescendantType, commonOptions,
        true, true, loadAlways);
    if (sync.sync().equals(FAILED)) {
      LOG.debug("Failed to load metadata for path from UFS: {}", path);
    }
  }

  boolean shouldLoadMetadataIfNotExists(LockedInodePath inodePath, LoadMetadataContext context) {
    boolean inodeExists = inodePath.fullPathExists();
    boolean loadDirectChildren = false;
    if (inodeExists) {
      try {
        Inode inode = inodePath.getInode();
        loadDirectChildren = inode.isDirectory()
            && (context.getOptions().getLoadDescendantType() != LoadDescendantPType.NONE);
      } catch (FileDoesNotExistException e) {
        // This should never happen.
        throw new RuntimeException(e);
      }
    }
    return !inodeExists || loadDirectChildren;
  }

  private void prepareForMount(AlluxioURI ufsPath, long mountId, MountContext context)
      throws IOException {
    MountPOptions.Builder mountOption = context.getOptions();
    try (CloseableResource<UnderFileSystem> ufsResource =
        mUfsManager.get(mountId).acquireUfsResource()) {
      UnderFileSystem ufs = ufsResource.get();
      // Check that the ufsPath exists and is a directory
      if (!ufs.isDirectory(ufsPath.toString())) {
        throw new IOException(
            MessageFormat.format("Ufs path {0} does not exist", ufsPath.toString()));
      }
      if (UnderFileSystemUtils.isWeb(ufs)) {
        mountOption.setReadOnly(true);
      }
    }
  }

  private void updateMountInternal(Supplier<JournalContext> journalContext,
      LockedInodePath inodePath, AlluxioURI ufsPath, MountInfo mountInfo, MountContext context)
      throws FileAlreadyExistsException, InvalidPathException, IOException {
    long newMountId = mMountTable.createUnusedMountId();
    // lock sync manager to ensure no sync point is added before the mount point is removed
    try (LockResource r = new LockResource(mSyncManager.getLock())) {
      List<AlluxioURI> syncPoints = mSyncManager.getFilterList(mountInfo.getMountId());
      if (syncPoints != null && !syncPoints.isEmpty()) {
        throw new InvalidArgumentException("Updating a mount point with ActiveSync enabled is not"
            + " supported. Please remove all sync'ed paths from the mount point and try again.");
      }
      AlluxioURI alluxioPath = inodePath.getUri();
      // validate new UFS client before updating the mount table
      mUfsManager.addMount(newMountId, new AlluxioURI(ufsPath.toString()),
          new UnderFileSystemConfiguration(
              Configuration.global(), context.getOptions().getReadOnly())
              .createMountSpecificConf(context.getOptions().getPropertiesMap()));
      prepareForMount(ufsPath, newMountId, context);
      // old ufsClient is removed as part of the mount table update process
      mMountTable.update(journalContext, alluxioPath, newMountId, context.getOptions().build());
    } catch (FileAlreadyExistsException | InvalidPathException | IOException e) {
      // revert everything
      mUfsManager.removeMount(newMountId);
      throw e;
    }
  }

  @Override
  public void updateMount(AlluxioURI alluxioPath, MountContext context)
      throws FileAlreadyExistsException, FileDoesNotExistException, InvalidPathException,
      IOException, AccessControlException {
    LockingScheme lockingScheme = createLockingScheme(alluxioPath,
        context.getOptions().getCommonOptions(), LockPattern.WRITE_EDGE);
    try (RpcContext rpcContext = createRpcContext(context);
        LockedInodePath inodePath = mInodeTree
            .lockInodePath(
                lockingScheme.getPath(),
                lockingScheme.getPattern(),
                rpcContext.getJournalContext()
            );
        FileSystemMasterAuditContext auditContext = createAuditContext(
            "updateMount", alluxioPath, null, inodePath.getParentInodeOrNull())) {
      try {
        mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, inodePath);
      } catch (AccessControlException e) {
        auditContext.setAllowed(false);
        throw e;
      }
      MountInfo mountInfo = mMountTable.getMountTable().get(alluxioPath.getPath());
      if (mountInfo == null) {
        throw new InvalidPathException("Failed to update mount properties for "
            + inodePath.getUri() + ". Please ensure the path is an existing mount point.");
      }
      updateMountInternal(rpcContext, inodePath, mountInfo.getUfsUri(), mountInfo, context);
      auditContext.setSucceeded(true);
    }
  }

  @Override
  public void mount(AlluxioURI alluxioPath, AlluxioURI ufsPath, MountContext context)
      throws FileAlreadyExistsException, FileDoesNotExistException, InvalidPathException,
      IOException, AccessControlException {
    Metrics.MOUNT_OPS.inc();
    Recorder recorder = context.getRecorder();
    recorder.record("mount command: alluxio fs mount {} {} option {}",
        alluxioPath, ufsPath, context);
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("mount", alluxioPath, null, null)) {
      ufsPath = new AlluxioURI(PathUtils.normalizePath(ufsPath.toString(), AlluxioURI.SEPARATOR));

      syncMetadata(rpcContext,
          alluxioPath,
          context.getOptions().getCommonOptions(),
          DescendantType.ONE,
          auditContext,
          LockedInodePath::getParentInodeOrNull
      );

      LockingScheme lockingScheme =
          createLockingScheme(alluxioPath, context.getOptions().getCommonOptions(),
              LockPattern.WRITE_EDGE);
      try (LockedInodePath inodePath =
               mInodeTree.lockInodePath(lockingScheme, rpcContext.getJournalContext())
      ) {
        auditContext.setSrcInode(inodePath.getParentInodeOrNull());
        try {
          mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, inodePath);
        } catch (AccessControlException e) {
          auditContext.setAllowed(false);
          throw e;
        }
        mMountTable.checkUnderWritableMountPoint(alluxioPath);

        if (context.getOptions().getRemount()) {
          LOG.info("Mount {} with remount options, so it will be unmounted first.",
              inodePath.getUri());
          unmountInternal(rpcContext, inodePath);
        }
        mountInternal(rpcContext, inodePath, ufsPath, context);
        auditContext.setSucceeded(true);
        Metrics.PATHS_MOUNTED.inc();
      }
    }
  }

  /**
   * Mounts a UFS path onto an Alluxio path.
   *
   * @param rpcContext the rpc context
   * @param inodePath the Alluxio path to mount to
   * @param ufsPath the UFS path to mount
   * @param context the mount context
   */
  private void mountInternal(RpcContext rpcContext, LockedInodePath inodePath, AlluxioURI ufsPath,
      MountContext context) throws InvalidPathException, FileAlreadyExistsException,
      FileDoesNotExistException, IOException, AccessControlException {
    // Check that the Alluxio Path does not exist
    if (inodePath.fullPathExists()) {
      // TODO(calvin): Add a test to validate this (ALLUXIO-1831)
      throw new InvalidPathException(
          ExceptionMessage.MOUNT_POINT_ALREADY_EXISTS.getMessage(inodePath.getUri()));
    }

    try (LockResource r = new LockResource(mMountTable.getWriteLock())) {
      long mountId = mMountTable.createUnusedMountId();
      mMountTable.validateMountPoint(inodePath.getUri(), ufsPath, mountId,
          context.getOptions().build());
      Recorder recorder = context.getRecorder();
      recorder.record("Acquired mount ID for the new mount point: {}", mountId);
      // get UfsManager prepared
      mUfsManager.addMount(mountId, new AlluxioURI(ufsPath.toString()),
          new UnderFileSystemConfiguration(
              Configuration.global(), context.getOptions().getReadOnly())
              .createMountSpecificConf(context.getOptions().getPropertiesMap()));
      prepareForMount(ufsPath, mountId, context);
      try {
        // This will create the directory at alluxioPath
        InodeSyncStream.loadMountPointDirectoryMetadata(rpcContext,
            inodePath,
            LoadMetadataContext.mergeFrom(
                LoadMetadataPOptions.newBuilder().setCreateAncestors(false)), getMountTable(),
            mountId, context.getOptions().getShared(), ufsPath, mUfsManager.get(mountId),
            this);
        recorder.record("Mount point {} created successfully",
            inodePath.getUri().getPath());
        // As we have verified the mount operation by calling MountTable.verifyMount, there won't
        // be any error thrown when doing MountTable.add
        mMountTable.addValidated(rpcContext, inodePath.getUri(), ufsPath, mountId,
            context.getOptions().build());
      } catch (Exception e) {
        // if exception happens, it indicates the failure of loadMetadata
        LOG.error("Failed to mount {} at {}: ", ufsPath, inodePath.getUri(), e);
        recorder.record("Failed to mount {} at {}: ",
            ufsPath, inodePath.getUri(), e.getMessage());
        mUfsManager.removeMount(mountId);
        throw e;
      }
    }
  }

  @Override
  public void unmount(AlluxioURI alluxioPath) throws FileDoesNotExistException,
      InvalidPathException, IOException, AccessControlException {
    Metrics.UNMOUNT_OPS.inc();
    // Unmount should lock the parent to remove the child inode.
    try (RpcContext rpcContext = createRpcContext();
        LockedInodePath inodePath = mInodeTree
            .lockInodePath(alluxioPath, LockPattern.WRITE_EDGE, rpcContext.getJournalContext());
        FileSystemMasterAuditContext auditContext =
            createAuditContext("unmount", alluxioPath, null, inodePath.getInodeOrNull())) {
      try {
        mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, inodePath);
      } catch (AccessControlException e) {
        auditContext.setAllowed(false);
        throw e;
      }
      unmountInternal(rpcContext, inodePath);
      auditContext.setSucceeded(true);
      Metrics.PATHS_UNMOUNTED.inc();
    }
  }

  /**
   * Unmounts a UFS path previously mounted onto an Alluxio path.
   *
   * This method does not delete blocks. Instead, it adds the to the passed-in block deletion
   * context so that the blocks can be deleted after the inode deletion journal entry has been
   * written. We cannot delete blocks earlier because the inode deletion may fail, leaving us with
   * inode containing deleted blocks.
   *
   * @param rpcContext the rpc context
   * @param inodePath the Alluxio path to unmount, must be a mount point
   */
  private void unmountInternal(RpcContext rpcContext, LockedInodePath inodePath)
      throws InvalidPathException, FileDoesNotExistException, IOException {
    if (!inodePath.fullPathExists()) {
      throw new FileDoesNotExistException(
          "Failed to unmount: Path " + inodePath.getUri() + " does not exist");
    }
    MountInfo mountInfo = mMountTable.getMountTable().get(inodePath.getUri().getPath());
    if (mountInfo == null) {
      throw new InvalidPathException("Failed to unmount " + inodePath.getUri() + ". Please ensure"
          + " the path is an existing mount point.");
    }
    mSyncManager.stopSyncForMount(rpcContext, mountInfo.getMountId());

    if (!mMountTable.delete(rpcContext, inodePath.getUri(), true)) {
      throw new InvalidPathException("Failed to unmount " + inodePath.getUri() + ". Please ensure"
          + " the path is an existing mount point and not root.");
    }
    try {
      // Use the internal delete API, setting {@code alluxioOnly} to true to prevent the delete
      // operations from being persisted in the UFS.
      deleteInternal(rpcContext, inodePath, DeleteContext
          .mergeFrom(DeletePOptions.newBuilder().setRecursive(true).setAlluxioOnly(true)), true);
    } catch (DirectoryNotEmptyException e) {
      throw new RuntimeException(String.format(
          "We should never see this exception because %s should never be thrown when recursive "
              + "is true.",
          e.getClass()));
    }
  }

  @Override
  public void setAcl(AlluxioURI path, SetAclAction action, List<AclEntry> entries,
      SetAclContext context)
      throws FileDoesNotExistException, AccessControlException, InvalidPathException, IOException {
    Metrics.SET_ACL_OPS.inc();
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext("setAcl", path, null, null)) {

      syncMetadata(rpcContext,
          path,
          context.getOptions().getCommonOptions(),
          context.getOptions().getRecursive() ? DescendantType.ALL : DescendantType.NONE,
          auditContext,
          LockedInodePath::getInodeOrNull
      );

      LockingScheme lockingScheme =
          createLockingScheme(path, context.getOptions().getCommonOptions(),
              LockPattern.WRITE_INODE);
      try (LockedInodePath inodePath =
               mInodeTree.lockInodePath(lockingScheme, rpcContext.getJournalContext());
      ) {
        mPermissionChecker.checkSetAttributePermission(inodePath, false, true, false);
        if (context.getOptions().getRecursive()) {
          try (LockedInodePathList descendants = mInodeTree.getDescendants(inodePath)) {
            for (LockedInodePath child : descendants) {
              mPermissionChecker.checkSetAttributePermission(child, false, true, false);
            }
          } catch (AccessControlException e) {
            auditContext.setAllowed(false);
            throw e;
          }
        }

        if (!inodePath.fullPathExists()) {
          throw new FileDoesNotExistException(ExceptionMessage
              .PATH_DOES_NOT_EXIST.getMessage(path));
        }
        setAclInternal(rpcContext, action, inodePath, entries, context);
        auditContext.setSucceeded(true);
      }
    }
  }

  private void setAclInternal(RpcContext rpcContext, SetAclAction action, LockedInodePath inodePath,
      List<AclEntry> entries, SetAclContext context)
      throws IOException, FileDoesNotExistException {
    Preconditions.checkState(inodePath.getLockPattern().isWrite());

    long opTimeMs = mClock.millis();
    // Check inputs for setAcl
    switch (action) {
      case REPLACE:
        Set<AclEntryType> types =
            entries.stream().map(AclEntry::getType).collect(Collectors.toSet());
        Set<AclEntryType> requiredTypes = Sets.newHashSet(AclEntryType.OWNING_USER,
            AclEntryType.OWNING_GROUP, AclEntryType.OTHER);
        requiredTypes.removeAll(types);

        // make sure the required entries are present
        if (!requiredTypes.isEmpty()) {
          throw new IOException(MessageFormat.format(
              "Replacing ACL entries must include the base entries for 'user', 'group',"
                  + " and 'other'. missing: {0}",
              requiredTypes.stream().map(AclEntryType::toString)
                  .collect(Collectors.joining(", "))));
        }
        break;
      case MODIFY: // fall through
      case REMOVE:
        if (entries.isEmpty()) {
          // Nothing to do.
          return;
        }
        break;
      case REMOVE_ALL:
        break;
      case REMOVE_DEFAULT:
        break;
      default:
    }
    setAclRecursive(rpcContext, action, inodePath, entries, false, opTimeMs, context);
  }

  private void setUfsAcl(LockedInodePath inodePath)
      throws InvalidPathException, AccessControlException {
    Inode inode = inodePath.getInodeOrNull();

    checkUfsMode(inodePath.getUri(), OperationType.WRITE);
    MountTable.Resolution resolution = mMountTable.resolve(inodePath.getUri());
    String ufsUri = resolution.getUri().toString();
    try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
      UnderFileSystem ufs = ufsResource.get();
      if (ufs.isObjectStorage()) {
        LOG.warn("SetACL is not supported to object storage UFS via Alluxio. "
            + "UFS: " + ufsUri + ". This has no effect on the underlying object.");
      } else {
        try {
          List<AclEntry> entries = new ArrayList<>(inode.getACL().getEntries());
          if (inode.isDirectory()) {
            entries.addAll(inode.asDirectory().getDefaultACL().getEntries());
          }
          ufs.setAclEntries(ufsUri, entries);
        } catch (IOException e) {
          throw new AccessControlException("Could not setAcl for UFS file: " + ufsUri);
        }
      }
    }
  }

  private void setAclSingleInode(RpcContext rpcContext, SetAclAction action,
      LockedInodePath inodePath, List<AclEntry> entries, boolean replay, long opTimeMs)
      throws IOException, FileDoesNotExistException {
    Preconditions.checkState(inodePath.getLockPattern().isWrite());

    Inode inode = inodePath.getInode();

    // Check that we are not removing an extended mask.
    if (action == SetAclAction.REMOVE) {
      for (AclEntry entry : entries) {
        if ((entry.isDefault() && inode.getDefaultACL().hasExtended())
            || (!entry.isDefault() && inode.getACL().hasExtended())) {
          if (entry.getType() == AclEntryType.MASK) {
            throw new InvalidArgumentException(
                "Deleting the mask for an extended ACL is not allowed. entry: " + entry);
          }
        }
      }
    }

    // Check that we are not setting default ACL to a file
    if (inode.isFile()) {
      for (AclEntry entry : entries) {
        if (entry.isDefault()) {
          throw new UnsupportedOperationException("Can not set default ACL for a file");
        }
      }
    }

    mInodeTree.setAcl(rpcContext, SetAclEntry.newBuilder()
        .setId(inode.getId())
        .setOpTimeMs(opTimeMs)
        .setAction(ProtoUtils.toProto(action))
        .addAllEntries(entries.stream().map(ProtoUtils::toProto).collect(Collectors.toList()))
        .build());

    try {
      if (!replay && inode.isPersisted()) {
        setUfsAcl(inodePath);
      }
    } catch (InvalidPathException | AccessControlException e) {
      LOG.warn("Setting ufs ACL failed for path: {}", inodePath.getUri(), e);
      // TODO(david): revert the acl and default acl to the initial state if writing to ufs failed.
    }
  }

  private void setAclRecursive(RpcContext rpcContext, SetAclAction action,
      LockedInodePath inodePath, List<AclEntry> entries, boolean replay, long opTimeMs,
      SetAclContext context) throws IOException, FileDoesNotExistException {
    Preconditions.checkState(inodePath.getLockPattern().isWrite());
    setAclSingleInode(rpcContext, action, inodePath, entries, replay, opTimeMs);
    if (context.getOptions().getRecursive()) {
      try (LockedInodePathList descendants = mInodeTree.getDescendants(inodePath)) {
        int journalFlushCounter = 0;
        for (LockedInodePath childPath : descendants) {
          rpcContext.throwIfCancelled();
          setAclSingleInode(rpcContext, action, childPath, entries, replay, opTimeMs);
          journalFlushCounter++;
          if (mMergeInodeJournals
              && journalFlushCounter > mRecursiveOperationForceFlushEntries) {
            rpcContext.getJournalContext().flush();
            journalFlushCounter = 0;
          }
        }
      }
    }
  }

  @Override
  public void setAttribute(AlluxioURI path, SetAttributeContext context)
      throws FileDoesNotExistException, AccessControlException, InvalidPathException, IOException {
    SetAttributePOptions.Builder options = context.getOptions();
    Metrics.SET_ATTRIBUTE_OPS.inc();
    // for chown
    boolean rootRequired = options.hasOwner();
    // for chgrp, chmod
    boolean ownerRequired = (options.hasGroup()) || (options.hasMode());
    // for other attributes
    boolean writeRequired = !rootRequired && !ownerRequired;
    if (options.hasOwner() && options.hasGroup()) {
      try {
        checkUserBelongsToGroup(options.getOwner(), options.getGroup());
      } catch (IOException e) {
        throw new IOException(String.format("Could not update owner:group for %s to %s:%s. %s",
            path.toString(), options.getOwner(), options.getGroup(), e), e);
      }
    }
    String commandName;
    boolean checkWritableMountPoint = false;
    if (options.hasOwner()) {
      commandName = "chown";
      checkWritableMountPoint = true;
    } else if (options.hasGroup()) {
      commandName = "chgrp";
      checkWritableMountPoint = true;
    } else if (options.hasMode()) {
      commandName = "chmod";
      checkWritableMountPoint = true;
    } else {
      commandName = "setAttribute";
    }
    try (RpcContext rpcContext = createRpcContext(context);
        FileSystemMasterAuditContext auditContext =
            createAuditContext(commandName, path, null, null)) {

      // Force recursive sync metadata if it is a pinning and unpinning operation
      boolean recursiveSync = options.hasPinned() || options.getRecursive();
      syncMetadata(rpcContext,
          path,
          context.getOptions().getCommonOptions(),
          recursiveSync ? DescendantType.ALL : DescendantType.ONE,
          auditContext,
          LockedInodePath::getInodeOrNull
      );

      LockingScheme lockingScheme = createLockingScheme(path, options.getCommonOptions(),
          LockPattern.WRITE_INODE);
      try (LockedInodePath inodePath =
               mInodeTree.lockInodePath(lockingScheme, rpcContext.getJournalContext());
      ) {
        auditContext.setSrcInode(inodePath.getInodeOrNull());
        if (checkWritableMountPoint) {
          mMountTable.checkUnderWritableMountPoint(path);
        }

        if (!inodePath.fullPathExists()) {
          throw new FileDoesNotExistException(ExceptionMessage
              .PATH_DOES_NOT_EXIST.getMessage(path));
        }
        try {
          mPermissionChecker
              .checkSetAttributePermission(inodePath, rootRequired, ownerRequired, writeRequired);
          if (context.getOptions().getRecursive()) {
            try (LockedInodePathList descendants = mInodeTree.getDescendants(inodePath)) {
              for (LockedInodePath childPath : descendants) {
                mPermissionChecker
                    .checkSetAttributePermission(childPath, rootRequired, ownerRequired,
                        writeRequired);
              }
            }
          }
        } catch (AccessControlException e) {
          auditContext.setAllowed(false);
          throw e;
        }
        setAttributeInternal(rpcContext, inodePath, context);
        auditContext.setSucceeded(true);
      }
    }
  }

  /**
   * Checks whether the owner belongs to the group.
   *
   * @param owner the owner to check
   * @param group the group to check
   * @throws FailedPreconditionException if owner does not belong to group
   */
  private void checkUserBelongsToGroup(String owner, String group)
      throws IOException {
    List<String> groups = CommonUtils.getGroups(owner, Configuration.global());
    if (groups == null || !groups.contains(group)) {
      throw new FailedPreconditionException("Owner " + owner
          + " does not belong to the group " + group);
    }
  }

  /**
   * Sets the file attribute.
   *
   * @param rpcContext the rpc context
   * @param inodePath the {@link LockedInodePath} to set attribute for
   * @param context attributes to be set, see {@link SetAttributePOptions}
   */
  private void setAttributeInternal(RpcContext rpcContext, LockedInodePath inodePath,
      SetAttributeContext context)
      throws InvalidPathException, FileDoesNotExistException, AccessControlException, IOException {
    Inode targetInode = inodePath.getInode();
    long opTimeMs = mClock.millis();
    if (context.getOptions().getRecursive() && targetInode.isDirectory()) {
      int journalFlushCounter = 0;
      try (LockedInodePathList descendants = mInodeTree.getDescendants(inodePath)) {
        for (LockedInodePath childPath : descendants) {
          rpcContext.throwIfCancelled();
          setAttributeSingleFile(rpcContext, childPath, true, opTimeMs, context);
          journalFlushCounter++;
          if (mMergeInodeJournals
              && journalFlushCounter > mRecursiveOperationForceFlushEntries) {
            rpcContext.getJournalContext().flush();
            journalFlushCounter = 0;
          }
        }
      }
    }
    setAttributeSingleFile(rpcContext, inodePath, true, opTimeMs, context);
  }

  @Override
  public void scheduleAsyncPersistence(AlluxioURI path, ScheduleAsyncPersistenceContext context)
      throws AlluxioException, UnavailableException {
    try (RpcContext rpcContext = createRpcContext(context);
        LockedInodePath inodePath =
            mInodeTree
                .lockFullInodePath(path, LockPattern.WRITE_INODE, rpcContext.getJournalContext())
    ) {
      InodeFile inode = inodePath.getInodeFile();
      if (!inode.isCompleted()) {
        throw new InvalidPathException(
            "Cannot persist an incomplete Alluxio file: " + inodePath.getUri());
      }
      scheduleAsyncPersistenceInternal(inodePath, context, rpcContext);
    }
  }

  /**
   * Persists an inode asynchronously.
   * This method does not do the completion check. When this method is invoked,
   * please make sure the inode has been completed.
   * Currently, two places call this method. One is completeFile(), where we know that
   * the file is completed. Another place is scheduleAsyncPersistence(), where we check
   * if the inode is completed and throws an exception if it is not.
   * @param inodePath the locked inode path
   * @param context the context
   * @param rpcContext the rpc context
   * @throws FileDoesNotExistException if the file does not exist
   */
  private void scheduleAsyncPersistenceInternal(LockedInodePath inodePath,
      ScheduleAsyncPersistenceContext context, RpcContext rpcContext)
      throws FileDoesNotExistException {
    InodeFile inode = inodePath.getInodeFile();
    if (shouldPersistPath(inodePath.toString())) {
      mInodeTree.updateInode(rpcContext, UpdateInodeEntry.newBuilder().setId(inode.getId())
          .setPersistenceState(PersistenceState.TO_BE_PERSISTED.name()).build());
      mPersistRequests.put(inode.getId(),
          new alluxio.time.ExponentialTimer(
              Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_INITIAL_INTERVAL_MS),
              Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_INTERVAL_MS),
              context.getPersistenceWaitTime(),
              Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_TOTAL_WAIT_TIME_MS)));
    }
  }

  /**
   * Actively sync metadata, based on a list of changed files.
   *
   * @param path the path to sync
   * @param changedFiles collection of files that are changed under the path to full sync if this is
   *        null, force sync the entire directory. if this is not null but an empty collection,
   *        this method does nothing.
   * @param executorService executor to execute the parallel incremental sync
   */
  @Override
  public void activeSyncMetadata(AlluxioURI path, @Nullable Collection<AlluxioURI> changedFiles,
      ExecutorService executorService) throws IOException {
    if (changedFiles == null) {
      LOG.info("Start an active full sync of {}", path.toString());
    } else {
      LOG.info("Start an active incremental sync of {} files", changedFiles.size());
    }
    long start = mClock.millis();

    if (changedFiles != null && changedFiles.isEmpty()) {
      return;
    }

    try (RpcContext rpcContext = createRpcContext()) {
      if (changedFiles == null) {
        // full sync
        // Set sync interval to 0 to force a sync.
        FileSystemMasterCommonPOptions options =
            FileSystemMasterCommonPOptions.newBuilder().setSyncIntervalMs(0).build();
        LockingScheme scheme = createSyncLockingScheme(path, options, DescendantType.ALL);
        InodeSyncStream sync = new InodeSyncStream(scheme, this, getSyncPathCache(), rpcContext,
            DescendantType.ALL, options, false, false, false);
        if (sync.sync().equals(FAILED)) {
          LOG.debug("Active full sync on {} didn't sync any paths.", path);
        }
        long end = mClock.millis();
        LOG.info("Ended an active full sync of {} in {}ms", path, end - start);
        return;
      } else {
        // incremental sync
        Set<Callable<Void>> callables = new HashSet<>();
        for (AlluxioURI changedFile : changedFiles) {
          callables.add(() -> {
            // Set sync interval to 0 to force a sync.
            FileSystemMasterCommonPOptions options =
                FileSystemMasterCommonPOptions.newBuilder().setSyncIntervalMs(0).build();
            LockingScheme scheme = createSyncLockingScheme(changedFile, options,
                DescendantType.ONE);
            InodeSyncStream sync = new InodeSyncStream(scheme,
                this, getSyncPathCache(), rpcContext,
                DescendantType.ONE, options, false, false, false);
            if (sync.sync().equals(FAILED)) {
              // Use debug because this can be a noisy log
              LOG.debug("Incremental sync on {} didn't sync any paths.", path);
            }
            return null;
          });
        }
        executorService.invokeAll(callables);
      }
    } catch (InterruptedException e) {
      LOG.warn("InterruptedException during active sync: {}", e.toString());
      Thread.currentThread().interrupt();
      return;
    } catch (InvalidPathException | AccessControlException e) {
      LogUtils.warnWithException(LOG, "Failed to active sync on path {}", path, e);
    }
    if (changedFiles != null) {
      long end = mClock.millis();
      LOG.info("Ended an active incremental sync of {} files in {}ms", changedFiles.size(),
          end - start);
    }
  }

  @Override
  public boolean recordActiveSyncTxid(long txId, long mountId) {
    MountInfo mountInfo = mMountTable.getMountInfo(mountId);
    if (mountInfo == null) {
      return false;
    }
    AlluxioURI mountPath = mountInfo.getAlluxioUri();

    try (RpcContext rpcContext = createRpcContext();
        LockedInodePath inodePath = mInodeTree
            .lockFullInodePath(mountPath, LockPattern.READ, rpcContext.getJournalContext())
    ) {
      File.ActiveSyncTxIdEntry txIdEntry =
          File.ActiveSyncTxIdEntry.newBuilder().setTxId(txId).setMountId(mountId).build();
      rpcContext.journal(JournalEntry.newBuilder().setActiveSyncTxId(txIdEntry).build());
    } catch (UnavailableException | InvalidPathException | FileDoesNotExistException e) {
      LOG.warn("Exception when recording activesync txid, path {}, exception {}",
          mountPath, e);
      return false;
    }
    return true;
  }

  /**
   * Sync metadata for an Alluxio path with the UFS.
   *
   * @param rpcContext the current RPC context
   * @param path the path to sync
   * @param options options included with the RPC
   * @param syncDescendantType how deep the sync should be performed
   * @param auditContextSrcInodeFunc the src inode for the audit context, if null, no source inode
   *                                 is set on the audit context
   * @return syncStatus
   */
  @VisibleForTesting
  InodeSyncStream.SyncStatus syncMetadata(RpcContext rpcContext, AlluxioURI path,
      FileSystemMasterCommonPOptions options, DescendantType syncDescendantType,
      @Nullable FileSystemMasterAuditContext auditContext,
      @Nullable Function<LockedInodePath, Inode> auditContextSrcInodeFunc)
      throws AccessControlException, InvalidPathException {
    LockingScheme syncScheme = createSyncLockingScheme(path, options, syncDescendantType);
    InodeSyncStream sync = new InodeSyncStream(syncScheme, this,
        getSyncPathCache(), rpcContext, syncDescendantType, options, auditContext,
        auditContextSrcInodeFunc, false, false, false);
    return sync.sync();
  }

  @Override
  public SyncMetadataPResponse syncMetadata(AlluxioURI path, SyncMetadataContext context)
      throws InvalidPathException {
    TaskGroup task = mDefaultSyncProcess.syncPath(path,
        GrpcUtils.fromProto(context.getOptions().getLoadDescendantType()),
        GrpcUtils.fromProto(context.getOptions().getDirectoryLoadType()), 0, null, true);
    try {
      task.waitAllComplete(0);
    } catch (Throwable t) {
      LOG.error("Sync metadata failed for task group {}", task.getGroupId(), t);
    }
    return SyncMetadataPResponse.newBuilder().addAllTask(
        task.toProtoTasks().collect(Collectors.toList())).build();
  }

  @Override
  public SyncMetadataAsyncPResponse syncMetadataAsync(AlluxioURI path, SyncMetadataContext context)
      throws InvalidPathException, IOException {
    TaskGroup result = mDefaultSyncProcess.syncPath(path,
        GrpcUtils.fromProto(context.getOptions().getLoadDescendantType()),
        GrpcUtils.fromProto(context.getOptions().getDirectoryLoadType()), 0, null, true);
    return SyncMetadataAsyncPResponse.newBuilder()
        .setSubmitted(true)
        .setTaskGroupId(result.getGroupId())
        .addAllTaskIds(result.getTasks().map(it -> it.getTaskInfo().getId())
            .collect(Collectors.toSet()))
        .build();
  }

  @Override
  public GetSyncProgressPResponse getSyncProgress(long taskGroupId) {
    Optional<TaskGroup> task = mDefaultSyncProcess.getTaskGroup(taskGroupId);
    if (!task.isPresent()) {
      throw new NotFoundRuntimeException("Task group id " + taskGroupId + " not found");
    }
    GetSyncProgressPResponse.Builder responseBuilder = GetSyncProgressPResponse.newBuilder();
    responseBuilder.addAllTask(task.get().toProtoTasks().collect(Collectors.toList()));

    return responseBuilder.build();
  }

  @Override
  public CancelSyncMetadataPResponse cancelSyncMetadata(long taskGroupId) throws NotFoundException {
    Optional<TaskGroup> group = mDefaultSyncProcess.getTaskGroup(taskGroupId);
    if (!group.isPresent()) {
      throw new NotFoundRuntimeException("Task group id " + taskGroupId + " not found");
    }
    Optional<NotFoundException> ex = group.get().getTasks().map(baseTask -> {
      try {
        mDefaultSyncProcess.getTaskTracker().cancelTaskById(baseTask.getTaskInfo().getId());
        return null;
      } catch (NotFoundException e) {
        return e;
      }
    }).filter(Objects::nonNull).reduce((acc, e) -> {
      acc.addSuppressed(e);
      return acc;
    });
    if (ex.isPresent()) {
      throw ex.get();
    }
    return CancelSyncMetadataPResponse.newBuilder().build();
  }

  @FunctionalInterface
  interface PermissionCheckFunction {

    /**
     * Performs this operation on the given arguments.
     *
     * @param l the first input argument
     * @param c the second input argument
     */
    void accept(LockedInodePath l, PermissionChecker c) throws AccessControlException,
        InvalidPathException;
  }

  ReadOnlyInodeStore getInodeStore() {
    return mInodeStore;
  }

  InodeTree getInodeTree() {
    return mInodeTree;
  }

  InodeLockManager getInodeLockManager() {
    return mInodeLockManager;
  }

  UfsAbsentPathCache getAbsentPathCache() {
    return mUfsAbsentPathCache;
  }

  PermissionChecker getPermissionChecker() {
    return mPermissionChecker;
  }

  @Override
  public FileSystemCommand workerHeartbeat(long workerId, List<Long> persistedFiles,
      WorkerHeartbeatContext context) throws IOException {

    List<String> persistedUfsFingerprints = context.getOptions().getPersistedFileFingerprintsList();
    boolean hasPersistedFingerprints = persistedUfsFingerprints.size() == persistedFiles.size();
    for (int i = 0; i < persistedFiles.size(); i++) {
      long fileId = persistedFiles.get(i);
      String ufsFingerprint = hasPersistedFingerprints ? persistedUfsFingerprints.get(i) :
          Constants.INVALID_UFS_FINGERPRINT;
      try {
        // Permission checking for each file is performed inside setAttribute
        setAttribute(getPath(fileId),
            SetAttributeContext
                .mergeFrom(SetAttributePOptions.newBuilder().setPersisted(true))
                .setUfsFingerprint(ufsFingerprint));
      } catch (FileDoesNotExistException | AccessControlException | InvalidPathException e) {
        LOG.error("Failed to set file {} as persisted, because {}", fileId, e);
      }
    }

    // TODO(zac) Clean up master and worker code since this is taken care of by job service now.
    // Worker should not persist any files. Instead, files are persisted through job service.
    List<PersistFile> filesToPersist = new ArrayList<>();
    FileSystemCommandOptions commandOptions = new FileSystemCommandOptions();
    commandOptions.setPersistOptions(new PersistCommandOptions(filesToPersist));
    return new FileSystemCommand(CommandType.PERSIST, commandOptions);
  }

  /**
   * @param rpcContext the rpc context
   * @param inodePath the {@link LockedInodePath} to use
   * @param updateUfs whether to update the UFS with the attribute change
   * @param opTimeMs the operation time (in milliseconds)
   * @param context the method context
   */
  public void setAttributeSingleFile(RpcContext rpcContext, LockedInodePath inodePath,
      boolean updateUfs, long opTimeMs, SetAttributeContext context)
      throws FileDoesNotExistException, InvalidPathException, AccessControlException {
    Inode inode = inodePath.getInode();
    SetAttributePOptions.Builder protoOptions = context.getOptions();
    if (inode.isDirectory() && protoOptions.hasDirectChildrenLoaded()) {
      mInodeTree.setDirectChildrenLoaded(
          rpcContext, inode.asDirectory(), protoOptions.getDirectChildrenLoaded());
    }
    if (protoOptions.hasPinned()) {
      mInodeTree.setPinned(rpcContext, inodePath, context.getOptions().getPinned(),
          context.getOptions().getPinnedMediaList(), opTimeMs);
    }
    UpdateInodeEntry.Builder entry = UpdateInodeEntry.newBuilder().setId(inode.getId());
    if (protoOptions.hasReplicationMax() || protoOptions.hasReplicationMin()) {
      Integer replicationMax =
          protoOptions.hasReplicationMax() ? protoOptions.getReplicationMax() : null;
      Integer replicationMin =
          protoOptions.hasReplicationMin() ? protoOptions.getReplicationMin() : null;
      mInodeTree.setReplication(rpcContext, inodePath, replicationMax, replicationMin, opTimeMs);
    }
    // protoOptions may not have both fields set
    if (protoOptions.hasCommonOptions()) {
      FileSystemMasterCommonPOptions commonOpts = protoOptions.getCommonOptions();
      TtlAction action = commonOpts.hasTtlAction() ? commonOpts.getTtlAction() : null;
      Long ttl = commonOpts.hasTtl() ? commonOpts.getTtl() : null;
      boolean modified = false;

      if (ttl != null && inode.getTtl() != ttl) {
        entry.setTtl(ttl);
        modified = true;
      }
      if (action != null && inode.getTtlAction() != action) {
        entry.setTtlAction(ProtobufUtils.toProtobuf(action));
        modified = true;
      }

      if (modified) {
        entry.setLastModificationTimeMs(opTimeMs);
      }
    }
    if (protoOptions.getXattrCount() > 0) {
      LOG.debug("Updating Inode={} with xAttr={}",
          inodePath.getInode(), protoOptions.getXattrMap());
      entry.putAllXAttr(protoOptions.getXattrMap());
      if (protoOptions.hasXattrUpdateStrategy()) {
        entry.setXAttrUpdateStrategy(protoOptions.getXattrUpdateStrategy());
      } // otherwise, uses the UpdateInodeEntry gRPC message default update strategy
    }
    if (protoOptions.hasPersisted()) {
      Preconditions.checkArgument(inode.isFile(), "Only files can be persisted");
      Preconditions.checkArgument(inode.asFile().isCompleted(),
          "File being persisted must be complete");
      // TODO(manugoyal) figure out valid behavior in the un-persist case
      Preconditions.checkArgument(protoOptions.getPersisted(),
          "Cannot set the state of a file to not-persisted");
      if (!inode.asFile().isPersisted()) {
        entry.setPersistenceState(PersistenceState.PERSISTED.name());
        entry.setLastModificationTimeMs(context.getOperationTimeMs());
        propagatePersistedInternal(rpcContext, inodePath);
        Metrics.FILES_PERSISTED.inc();
      }
    }
    boolean ownerGroupChanged = (protoOptions.hasOwner()) || (protoOptions.hasGroup());
    boolean modeChanged = protoOptions.hasMode();
    // If the file is persisted in UFS, also update corresponding owner/group/permission.
    if ((ownerGroupChanged || modeChanged) && updateUfs && inode.isPersisted()) {
      if (ownerGroupChanged) {
        LOG.info("Updating inode '{}' owner group from ({}:{}) to ({}:{})", inode.getName(),
            inode.getOwner(), inode.getGroup(), protoOptions.getOwner(), protoOptions.getGroup());
      }
      if (modeChanged) {
        LOG.info("Updating inode '{}' mode bits from {} to {}", inode.getName(),
            new Mode(inode.getMode()),
            new Mode(ModeUtils.protoToShort(protoOptions.getMode())));
      }
      if ((inode instanceof InodeFile) && !inode.asFile().isCompleted()) {
        LOG.warn("Alluxio does not propagate chown/chgrp/chmod to UFS for incomplete files.");
      } else {
        checkUfsMode(inodePath.getUri(), OperationType.WRITE);
        MountTable.Resolution resolution = mMountTable.resolve(inodePath.getUri());
        String ufsUri = resolution.getUri().toString();
        try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
          UnderFileSystem ufs = ufsResource.get();
          if (ufs.isObjectStorage()) {
            LOG.warn("setOwner/setMode is not supported to object storage UFS via Alluxio. "
                + "UFS: " + ufsUri + ". This has no effect on the underlying object.");
          } else {
            String owner = null;
            String group = null;
            String mode = null;
            if (ownerGroupChanged) {
              try {
                owner =
                    protoOptions.hasOwner() ? protoOptions.getOwner() : inode.getOwner();
                group =
                    protoOptions.hasGroup() ? protoOptions.getGroup() : inode.getGroup();
                ufs.setOwner(ufsUri, owner, group);
              } catch (IOException e) {
                throw new AccessControlException("Could not setOwner for UFS file " + ufsUri
                    + " . Aborting the setAttribute operation in Alluxio.", e);
              }
            }
            if (modeChanged) {
              try {
                mode = String.valueOf(protoOptions.getMode());
                ufs.setMode(ufsUri, ModeUtils.protoToShort(protoOptions.getMode()));
              } catch (IOException e) {
                throw new AccessControlException("Could not setMode for UFS file " + ufsUri
                    + " . Aborting the setAttribute operation in Alluxio.", e);
              }
            }
            // Retrieve the ufs fingerprint after the ufs changes.
            String existingFingerprint = inode.getUfsFingerprint();
            if (!existingFingerprint.equals(Constants.INVALID_UFS_FINGERPRINT)) {
              // Update existing fingerprint, since contents did not change
              Fingerprint fp = Fingerprint.parse(existingFingerprint);
              fp.putTag(Fingerprint.Tag.OWNER, owner);
              fp.putTag(Fingerprint.Tag.GROUP, group);
              fp.putTag(Fingerprint.Tag.MODE, mode);
              context.setUfsFingerprint(fp.serialize());
            } else {
              // Need to retrieve the fingerprint from ufs.
              context.setUfsFingerprint(ufs.getParsedFingerprint(ufsUri).serialize());
            }
          }
        }
      }
    }
    if (!context.getUfsFingerprint().equals(Constants.INVALID_UFS_FINGERPRINT)) {
      entry.setUfsFingerprint(context.getUfsFingerprint());
    }
    // Only commit the set permission to inode after the propagation to UFS succeeded.
    if (protoOptions.hasOwner()) {
      entry.setOwner(protoOptions.getOwner());
    }
    if (protoOptions.hasGroup()) {
      entry.setGroup(protoOptions.getGroup());
    }
    if (modeChanged) {
      entry.setMode(ModeUtils.protoToShort(protoOptions.getMode()));
    }
    mInodeTree.updateInode(rpcContext, entry.build());
  }

  @Override
  public List<SyncPointInfo> getSyncPathList() {
    return mSyncManager.getSyncPathList();
  }

  @Override
  public void startSync(AlluxioURI syncPoint)
      throws IOException, InvalidPathException, AccessControlException, ConnectionFailedException {
    LockingScheme lockingScheme = new LockingScheme(syncPoint, LockPattern.WRITE_EDGE, true);
    try (RpcContext rpcContext = createRpcContext();
        LockedInodePath inodePath = mInodeTree
            .lockInodePath(
                lockingScheme.getPath(),
                lockingScheme.getPattern(),
                rpcContext.getJournalContext()
            );
        FileSystemMasterAuditContext auditContext =
            createAuditContext("startSync", syncPoint, null,
                inodePath.getParentInodeOrNull())) {
      try {
        mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, inodePath);
      } catch (AccessControlException e) {
        auditContext.setAllowed(false);
        throw e;
      }
      mSyncManager.startSyncAndJournal(rpcContext, syncPoint);
      auditContext.setSucceeded(true);
    }
  }

  @Override
  public void stopSync(AlluxioURI syncPoint)
      throws IOException, InvalidPathException, AccessControlException {
    try (RpcContext rpcContext = createRpcContext()) {
      boolean isSuperUser = true;
      try {
        mPermissionChecker.checkSuperUser();
      } catch (AccessControlException e) {
        isSuperUser = false;
      }

      if (isSuperUser) {
        // TODO(AM): Remove once we don't require a write lock on the sync point during a full sync
        // Stop sync w/o acquiring an inode lock to terminate an initial full scan (if running)
        mSyncManager.stopSyncAndJournal(rpcContext, syncPoint);
      }
      LockingScheme lockingScheme = new LockingScheme(syncPoint, LockPattern.READ, false);
      try (LockedInodePath inodePath =
          mInodeTree
              .lockInodePath(
                  lockingScheme.getPath(),
                  lockingScheme.getPattern(),
                  rpcContext.getJournalContext()
              );
          FileSystemMasterAuditContext auditContext =
              createAuditContext("stopSync", syncPoint, null,
                  inodePath.getParentInodeOrNull())) {
        try {
          mPermissionChecker.checkParentPermission(Mode.Bits.WRITE, inodePath);
        } catch (AccessControlException e) {
          auditContext.setAllowed(false);
          throw e;
        }
        if (!isSuperUser) {
          // Stop sync here only if not terminated w/o holding the inode lock
          mSyncManager.stopSyncAndJournal(rpcContext, syncPoint);
        }
        auditContext.setSucceeded(true);
      }
    }
  }

  @Override
  public List<WorkerInfo> getWorkerInfoList() throws UnavailableException {
    return mBlockMaster.getWorkerInfoList();
  }

  @Override
  public long getInodeCount() {
    return mInodeTree.getInodeCount();
  }

  /**
   * @param fileId file ID
   * @param jobId persist job ID
   * @param persistenceWaitTime persistence initial wait time
   * @param uri Alluxio Uri of the file
   * @param tempUfsPath temp UFS path
   */
  private void addPersistJob(long fileId, long jobId, long persistenceWaitTime, AlluxioURI uri,
      String tempUfsPath) {
    alluxio.time.ExponentialTimer timer = mPersistRequests.remove(fileId);
    if (timer == null) {
      timer = new alluxio.time.ExponentialTimer(
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_INITIAL_INTERVAL_MS),
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_INTERVAL_MS),
          persistenceWaitTime,
          Configuration.getMs(PropertyKey.MASTER_PERSISTENCE_MAX_TOTAL_WAIT_TIME_MS));
    }
    mPersistJobs.put(fileId, new PersistJob(jobId, fileId, uri, tempUfsPath, timer));
  }

  private long getPersistenceWaitTime(long shouldPersistTime) {
    long currentTime = mClock.millis();
    if (shouldPersistTime >= currentTime) {
      return shouldPersistTime - currentTime;
    } else {
      return 0;
    }
  }

  /**
   * Periodically schedules jobs to persist files and updates metadata accordingly.
   */
  @NotThreadSafe
  private final class PersistenceScheduler implements alluxio.heartbeat.HeartbeatExecutor {
    private static final long MAX_QUIET_PERIOD_SECONDS = 64;

    /**
     * Quiet period for job service flow control (in seconds). When job service refuses starting new
     * jobs, we use exponential backoff to alleviate the job service pressure.
     */
    private long mQuietPeriodSeconds;

    /**
     * Creates a new instance of {@link PersistenceScheduler}.
     */
    PersistenceScheduler() {
      mQuietPeriodSeconds = 0;
    }

    @Override
    public void close() {} // Nothing to clean up

    /**
     * Updates the file system metadata to reflect the fact that the persist file request expired.
     *
     * @param fileId the file ID
     */
    private void handleExpired(long fileId, JournalContext journalContext,
        AtomicInteger journalCount) throws AlluxioException {
      try (LockedInodePath inodePath = mInodeTree
          .lockFullInodePath(fileId, LockPattern.WRITE_INODE, journalContext)) {
        InodeFile inode = inodePath.getInodeFile();
        switch (inode.getPersistenceState()) {
          case LOST:
            // fall through
          case NOT_PERSISTED:
            // fall through
          case PERSISTED:
            LOG.warn("File {} (id={}) persistence state is {} and will not be changed.",
                inodePath.getUri(), fileId, inode.getPersistenceState());
            return;
          case TO_BE_PERSISTED:
            mInodeTree.updateInode(journalContext, UpdateInodeEntry.newBuilder()
                .setId(inode.getId())
                .setPersistenceState(PersistenceState.NOT_PERSISTED.name())
                .build());
            mInodeTree.updateInodeFile(journalContext, UpdateInodeFileEntry.newBuilder()
                .setId(inode.getId())
                .setPersistJobId(Constants.PERSISTENCE_INVALID_JOB_ID)
                .setTempUfsPath(Constants.PERSISTENCE_INVALID_UFS_PATH)
                .build());
            journalCount.addAndGet(2);
            break;
          default:
            throw new IllegalStateException(
                "Unrecognized persistence state: " + inode.getPersistenceState());
        }
      }
    }

    /**
     * Attempts to schedule a persist job and updates the file system metadata accordingly.
     *
     * @param fileId the file ID
     */
    private void handleReady(long fileId, JournalContext journalContext, AtomicInteger journalCount)
        throws AlluxioException, IOException {
      alluxio.time.ExponentialTimer timer = mPersistRequests.get(fileId);
      // Lookup relevant file information.
      AlluxioURI uri;
      String tempUfsPath;
      try (LockedInodePath inodePath
               = mInodeTree.lockFullInodePath(
                   fileId, LockPattern.READ, NoopJournalContext.INSTANCE)
      ) {
        InodeFile inode = inodePath.getInodeFile();
        uri = inodePath.getUri();
        switch (inode.getPersistenceState()) {
          case LOST:
            // fall through
          case NOT_PERSISTED:
            // fall through
          case PERSISTED:
            LOG.warn("File {} (id={}) persistence state is {} and will not be changed.",
                inodePath.getUri(), fileId, inode.getPersistenceState());
            return;
          case TO_BE_PERSISTED:
            tempUfsPath = inodePath.getInodeFile().getTempUfsPath();
            break;
          default:
            throw new IllegalStateException(
                "Unrecognized persistence state: " + inode.getPersistenceState());
        }
      }

      MountTable.Resolution resolution = mMountTable.resolve(uri);
      try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
        // If previous persist job failed, clean up the temporary file.
        cleanup(ufsResource.get(), tempUfsPath);
        // Generate a temporary path to be used by the persist job.
        // If the persist destination is on object store, let persist job copy files to destination
        // directly
        if (Configuration.getBoolean(PropertyKey.MASTER_UNSAFE_DIRECT_PERSIST_OBJECT_ENABLED)
            && ufsResource.get().isObjectStorage()) {
          tempUfsPath = resolution.getUri().toString();
        } else {
          // make temp path for temp file to avoid the
          // error reading (failure of temp file clean up)
          String mountPointUri = resolution.getUfsMountPointUri().toString();
          tempUfsPath = PathUtils.concatUfsPath(mountPointUri,
              PathUtils.getPersistentTmpPath(ufsResource.get().getConfiguration(),
                  resolution.getUri().toString()));
          LOG.debug("Generate tmp ufs path {} from ufs path {} for persistence.",
              tempUfsPath, resolution.getUri());
        }
      }

      PersistConfig config =
          new PersistConfig(uri.getPath(), resolution.getMountId(), false, tempUfsPath);
      // Schedule the persist job.
      long jobId;
      JobMasterClient client = mJobMasterClientPool.acquire();
      try {
        LOG.debug("Schedule async persist job for {}", uri.getPath());
        jobId = client.run(config);
      } finally {
        mJobMasterClientPool.release(client);
      }
      mQuietPeriodSeconds /= 2;
      mPersistJobs.put(fileId, new PersistJob(jobId, fileId, uri, tempUfsPath, timer));

      // Update the inode and journal the change.
      try (LockedInodePath inodePath = mInodeTree
          .lockFullInodePath(fileId, LockPattern.WRITE_INODE, journalContext)) {
        InodeFile inode = inodePath.getInodeFile();
        mInodeTree.updateInodeFile(journalContext, UpdateInodeFileEntry.newBuilder()
            .setId(inode.getId())
            .setPersistJobId(jobId)
            .setTempUfsPath(tempUfsPath)
            .build());
        journalCount.incrementAndGet();
      }
    }

    /**
     * {@inheritDoc}
     *
     * The method iterates through the set of files to be persisted (identified by their ID) and
     * attempts to schedule a file persist job. Each iteration removes the file ID from the set
     * of files to be persisted unless the execution sets the {@code remove} flag to false.
     *
     * @throws InterruptedException if the thread is interrupted
     */
    @Override
    public void heartbeat(long timeLimitMs) throws InterruptedException {
      LOG.debug("Async Persist heartbeat start");
      java.util.concurrent.TimeUnit.SECONDS.sleep(mQuietPeriodSeconds);
      AtomicInteger journalCounter = new AtomicInteger(0);
      try (JournalContext journalContext = createJournalContext()) {
        // Process persist requests.
        for (long fileId : mPersistRequests.keySet()) {
          if (journalCounter.get() > 100) {
            // The only exception thrown from flush() will be UnavailableException
            // See catch (UnavailableException e)
            journalContext.flush();
            journalCounter.set(0);
          }
          // Throw if interrupted.
          if (Thread.interrupted()) {
            throw new InterruptedException("PersistenceScheduler interrupted.");
          }
          boolean remove = true;
          alluxio.time.ExponentialTimer timer = mPersistRequests.get(fileId);
          if (timer == null) {
            // This could occur if a key is removed from mPersistRequests while we are iterating.
            continue;
          }
          alluxio.time.ExponentialTimer.Result timerResult = timer.tick();
          if (timerResult == alluxio.time.ExponentialTimer.Result.NOT_READY) {
            // operation is not ready to be scheduled
            continue;
          }
          AlluxioURI uri = null;
          try {
            try (LockedInodePath inodePath = mInodeTree
                .lockFullInodePath(fileId, LockPattern.READ, NoopJournalContext.INSTANCE)) {
              uri = inodePath.getUri();
            } catch (FileDoesNotExistException e) {
              LOG.debug("The file (id={}) to be persisted was not found. Likely this file has been "
                  + "removed by users", fileId, e);
              continue;
            }
            try {
              checkUfsMode(uri, OperationType.WRITE);
            } catch (Exception e) {
              LOG.warn("Unable to schedule persist request for path {}: {}", uri, e.toString());
              // Retry when ufs mode permits operation
              remove = false;
              continue;
            }
            switch (timerResult) {
              case EXPIRED:
                handleExpired(fileId, journalContext, journalCounter);
                break;
              case READY:
                handleReady(fileId, journalContext, journalCounter);
                break;
              default:
                throw new IllegalStateException("Unrecognized timer state: " + timerResult);
            }
          } catch (FileDoesNotExistException | InvalidPathException e) {
            LOG.warn("The file {} (id={}) to be persisted was not found : {}", uri, fileId,
                e.toString());
            LOG.debug("Exception: ", e);
          } catch (ResourceExhaustedException e) {
            LOG.warn("The job service is busy, will retry later: {}", e.toString());
            LOG.debug("Exception: ", e);
            mQuietPeriodSeconds = (mQuietPeriodSeconds == 0) ? 1 :
                Math.min(MAX_QUIET_PERIOD_SECONDS, mQuietPeriodSeconds * 2);
            remove = false;
            // End the method here until the next heartbeat. No more jobs should be scheduled during
            // the current heartbeat if the job master is at full capacity.
            return;
          } catch (Exception e) {
            LOG.warn("Unexpected exception encountered when scheduling the persist job for file {} "
                + "(id={}) : {}", uri, fileId, e.toString());
            LOG.debug("Exception: ", e);
          } finally {
            if (remove) {
              mPersistRequests.remove(fileId);
            }
          }
        }
      } catch (UnavailableException e) {
        // Two ways to arrive here:
        // 1. createJournalContext() fails, the batch processing has not started yet
        // 2. flush() fails and the queue is dirty, the JournalContext will be closed and flushed,
        //    but the flush will not succeed
        // The context is MasterJournalContext, so an UnavailableException indicates either
        // the primary failed over, or journal is closed
        // In either case, it is fine to close JournalContext and throw away the journal entries
        // The next primary will process all TO_BE_PERSISTED files and create new persist jobs
        LOG.error("Journal is not running, cannot persist files");
      }
    }
  }

  /**
   * Periodically polls for the result of the jobs and updates metadata accordingly.
   */
  @NotThreadSafe
  private final class PersistenceChecker implements alluxio.heartbeat.HeartbeatExecutor {

    /**
     * Creates a new instance of {@link PersistenceChecker}.
     */
    PersistenceChecker() {}

    @Override
    public void close() {} // nothing to clean up

    /**
     * Updates the file system metadata to reflect the fact that the persist job succeeded.
     *
     * NOTE: It is the responsibility of the caller to update {@link #mPersistJobs}.
     *
     * @param job the successful job
     */
    private void handleSuccess(PersistJob job) {
      long fileId = job.getFileId();
      String tempUfsPath = job.getTempUfsPath();
      List<Long> blockIds = new ArrayList<>();
      UfsManager.UfsClient ufsClient = null;
      // This journal flush is per job and cannot be batched easily,
      // because each execution is in a separate thread and this thread doesn't wait for those
      // to complete
      try (JournalContext journalContext = createJournalContext();
          LockedInodePath inodePath = mInodeTree
              .lockFullInodePath(fileId, LockPattern.WRITE_INODE, journalContext)) {
        InodeFile inode = inodePath.getInodeFile();
        MountTable.Resolution resolution = mMountTable.resolve(inodePath.getUri());
        ufsClient = mUfsManager.get(resolution.getMountId());
        switch (inode.getPersistenceState()) {
          case LOST:
            // fall through
          case NOT_PERSISTED:
            // fall through
          case PERSISTED:
            LOG.warn("File {} (id={}) persistence state is {}. Successful persist has no effect.",
                job.getUri(), fileId, inode.getPersistenceState());
            break;
          case TO_BE_PERSISTED:
            UpdateInodeEntry.Builder builder = UpdateInodeEntry.newBuilder();
            try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
              UnderFileSystem ufs = ufsResource.get();
              String ufsPath = resolution.getUri().toString();
              ufs.setOwner(tempUfsPath, inode.getOwner(), inode.getGroup());
              ufs.setMode(tempUfsPath, inode.getMode());

              // Check if the size is the same to guard against a race condition where the Alluxio
              // file is mutated in between the persist command and execution
              if (Configuration.isSet(PropertyKey.MASTER_ASYNC_PERSIST_SIZE_VALIDATION)
                  && Configuration.getBoolean(
                      PropertyKey.MASTER_ASYNC_PERSIST_SIZE_VALIDATION)) {
                UfsStatus ufsStatus = ufs.getStatus(tempUfsPath);
                if (ufsStatus.isFile()) {
                  UfsFileStatus status = (UfsFileStatus) ufsStatus;
                  if (status.getContentLength() != inode.getLength()) {
                    throw new IOException(String.format("%s size does not match. Alluxio expected "
                        + "length: %d, UFS actual length: %d. This may be due to a concurrent "
                        + "modification to the file in Alluxio space, in which case this error can "
                        + "be safely ignored as the persist will be retried. If the UFS length is "
                        + "expected to be different than Alluxio length, set "
                        + PropertyKey.Name.MASTER_ASYNC_PERSIST_SIZE_VALIDATION + " to false.",
                        tempUfsPath, inode.getLength(), status.getContentLength()));
                  }
                }
              }

              if (!ufsPath.equals(tempUfsPath)) {
                // Make rename only when tempUfsPath is different from final ufsPath. Note that,
                // on object store, we take the optimization to skip the rename by having
                // tempUfsPath the same as final ufsPath.
                // check if the destination direction is valid, if there isn't exist directory,
                // create it and it's parents
                createParentPath(inodePath.getInodeList(), ufsPath, ufs, job.getId());
                if (!ufs.renameRenamableFile(tempUfsPath, ufsPath)) {
                  throw new IOException(
                      String.format("Failed to rename %s to %s.", tempUfsPath, ufsPath));
                }
              }
              builder.setUfsFingerprint(ufs.getParsedFingerprint(ufsPath).serialize());
            }

            mInodeTree.updateInodeFile(journalContext, UpdateInodeFileEntry.newBuilder()
                .setId(inode.getId())
                .setPersistJobId(Constants.PERSISTENCE_INVALID_JOB_ID)
                .setTempUfsPath(Constants.PERSISTENCE_INVALID_UFS_PATH)
                .build());
            mInodeTree.updateInode(journalContext, builder
                .setId(inode.getId())
                .setPersistenceState(PersistenceState.PERSISTED.name())
                .build());
            propagatePersistedInternal(journalContext, inodePath);
            mUfsAbsentPathCache.processExisting(inodePath.getUri());
            Metrics.FILES_PERSISTED.inc();

            // Save state for possible cleanup
            blockIds.addAll(inode.getBlockIds());
            break;
          default:
            throw new IllegalStateException(
                "Unrecognized persistence state: " + inode.getPersistenceState());
        }
      } catch (FileDoesNotExistException | InvalidPathException e) {
        LOG.warn("The file {} (id={}) to be persisted was not found: {}", job.getUri(), fileId,
            e.toString());
        LOG.debug("Exception: ", e);
        // Cleanup the temporary file.
        if (ufsClient != null) {
          try (CloseableResource<UnderFileSystem> ufsResource = ufsClient.acquireUfsResource()) {
            cleanup(ufsResource.get(), tempUfsPath);
          }
        }
      } catch (Exception e) {
        LOG.warn(
            "Unexpected exception encountered when trying to complete persistence of a file {} "
                + "(id={}) : {}",
            job.getUri(), fileId, e.toString());
        LOG.debug("Exception: ", e);
        if (ufsClient != null) {
          try (CloseableResource<UnderFileSystem> ufsResource = ufsClient.acquireUfsResource()) {
            cleanup(ufsResource.get(), tempUfsPath);
          }
        }
        mPersistRequests.put(fileId, job.getTimer());
      }

      // Cleanup possible staging UFS blocks files due to fast durable write fallback.
      // Note that this is best effort
      if (ufsClient != null) {
        for (long blockId : blockIds) {
          String ufsBlockPath = alluxio.worker.BlockUtils.getUfsBlockPath(ufsClient, blockId);
          try (CloseableResource<UnderFileSystem> ufsResource = ufsClient.acquireUfsResource()) {
            alluxio.util.UnderFileSystemUtils.deleteFileIfExists(ufsResource.get(), ufsBlockPath);
          } catch (Exception e) {
            LOG.warn("Failed to clean up staging UFS block file {}: {}",
                ufsBlockPath, e.toString());
          }
        }
      }
    }

    /**
     * Create parent path if there isn't exiting ancestors path for final persistence file.
     *
     * @param inodes List of inodes
     * @param ufsPath ufs path
     * @param ufs under file system
     */
    private void createParentPath(List<Inode> inodes, String ufsPath,
        UnderFileSystem ufs, long jobId)
        throws IOException {
      Stack<Pair<String, Inode>> ancestors = new Stack<>();
      int curInodeIndex = inodes.size() - 2;
      // get file path
      AlluxioURI curUfsPath = new AlluxioURI(ufsPath);
      // get the parent path of current file
      curUfsPath = curUfsPath.getParent();
      // Stop when the directory already exists in UFS.
      while (!ufs.isDirectory(curUfsPath.toString()) && curInodeIndex >= 0) {
        Inode curInode = inodes.get(curInodeIndex);
        ancestors.push(new Pair<>(curUfsPath.toString(), curInode));
        curUfsPath = curUfsPath.getParent();
        curInodeIndex--;
      }

      while (!ancestors.empty()) {
        Pair<String, Inode> ancestor = ancestors.pop();
        String dir = ancestor.getFirst();
        Inode ancestorInode = ancestor.getSecond();
        MkdirsOptions options = MkdirsOptions.defaults(Configuration.global())
            .setCreateParent(false)
            .setOwner(ancestorInode.getOwner())
            .setGroup(ancestorInode.getGroup())
            .setMode(new Mode(ancestorInode.getMode()));
        // UFS mkdirs might fail if the directory is already created.
        // If so, skip the mkdirs and assume the directory is already prepared,
        // regardless of permission matching.
        boolean mkdirSuccess = false;
        try {
          try {
            mkdirSuccess = ufs.mkdirs(dir, options);
          } catch (IOException e) {
            LOG.debug("Persistence job {}: Exception Directory {}: ", jobId, dir, e);
          }
          if (mkdirSuccess) {
            List<AclEntry> allAcls =
                Stream.concat(ancestorInode.getDefaultACL().getEntries().stream(),
                    ancestorInode.getACL().getEntries().stream())
                    .collect(Collectors.toList());
            ufs.setAclEntries(dir, allAcls);
          } else {
            if (ufs.isDirectory(dir)) {
              LOG.debug("Persistence job {}: UFS directory {} already exists", jobId, dir);
            } else {
              LOG.error("Persistence job {}: UFS path {} is an existing file", jobId, dir);
            }
          }
        } catch (IOException e) {
          LOG.error("Persistence job {}: Failed to create UFS directory {} with correct permission",
              jobId, dir, e);
        }
      }
    }

    @Override
    public void heartbeat(long timeLimitMs) throws InterruptedException {
      boolean queueEmpty = mPersistCheckerPool.getQueue().isEmpty();
      // Check the progress of persist jobs.
      for (long fileId : mPersistJobs.keySet()) {
        // Throw if interrupted.
        if (Thread.interrupted()) {
          throw new InterruptedException("PersistenceChecker interrupted.");
        }
        final PersistJob job = mPersistJobs.get(fileId);
        if (job == null) {
          // This could happen if a key is removed from mPersistJobs while we are iterating.
          continue;
        }
        // Cancel any jobs marked as canceled
        switch (job.getCancelState()) {
          case NOT_CANCELED:
            break;
          case TO_BE_CANCELED:
            // Send the message to cancel this job
            JobMasterClient client = mJobMasterClientPool.acquire();
            try {
              client.cancel(job.getId());
              job.setCancelState(PersistJob.CancelState.CANCELING);
            } catch (alluxio.exception.status.NotFoundException e) {
              LOG.warn("Persist job (id={}) for file {} (id={}) to cancel was not found: {}",
                  job.getId(), job.getUri(), fileId, e.toString());
              LOG.debug("Exception: ", e);
              mPersistJobs.remove(fileId);
              continue;
            } catch (Exception e) {
              LOG.warn("Unexpected exception encountered when cancelling a persist job (id={}) for "
                  + "file {} (id={}) : {}", job.getId(), job.getUri(), fileId, e.toString());
              LOG.debug("Exception: ", e);
            } finally {
              mJobMasterClientPool.release(client);
            }
            continue;
          case CANCELING:
            break;
          default:
            throw new IllegalStateException("Unrecognized cancel state: " + job.getCancelState());
        }
        if (!queueEmpty) {
          // There are tasks waiting in the queue, so do not try to schedule anything
          continue;
        }
        long jobId = job.getId();
        JobMasterClient client = mJobMasterClientPool.acquire();
        try {
          JobInfo jobInfo = client.getJobStatus(jobId);
          switch (jobInfo.getStatus()) {
            case RUNNING:
              // fall through
            case CREATED:
              break;
            case FAILED:
              LOG.warn("The persist job (id={}) for file {} (id={}) failed: {}", jobId,
                  job.getUri(), fileId, jobInfo.getErrorMessage());
              mPersistJobs.remove(fileId);
              mPersistRequests.put(fileId, job.getTimer());
              break;
            case CANCELED:
              mPersistJobs.remove(fileId);
              break;
            case COMPLETED:
              mPersistJobs.remove(fileId);
              mPersistCheckerPool.execute(() -> handleSuccess(job));
              break;
            default:
              throw new IllegalStateException("Unrecognized job status: " + jobInfo.getStatus());
          }
        } catch (Exception e) {
          LOG.warn("Exception encountered when trying to retrieve the status of a "
                  + " persist job (id={}) for file {} (id={}): {}.", jobId, job.getUri(), fileId,
              e.toString());
          LOG.debug("Exception: ", e);
          mPersistJobs.remove(fileId);
          mPersistRequests.put(fileId, job.getTimer());
        } finally {
          mJobMasterClientPool.release(client);
        }
      }
    }
  }

  @NotThreadSafe
  private final class TimeSeriesRecorder implements alluxio.heartbeat.HeartbeatExecutor {
    @Override
    public void heartbeat(long timeLimitMs) throws InterruptedException {
      // TODO(calvin): Provide a better way to keep track of metrics collected as time series
      MetricRegistry registry = MetricsSystem.METRIC_REGISTRY;
      SortedMap<String, Gauge> gauges = registry.getGauges();

      // % Alluxio space used
      Long masterCapacityTotal = (Long) gauges
          .get(MetricKey.CLUSTER_CAPACITY_TOTAL.getName()).getValue();
      Long masterCapacityUsed = (Long) gauges
          .get(MetricKey.CLUSTER_CAPACITY_USED.getName()).getValue();
      int percentAlluxioSpaceUsed =
          (masterCapacityTotal > 0) ? (int) (100L * masterCapacityUsed / masterCapacityTotal) : 0;
      mTimeSeriesStore.record("% Alluxio Space Used", percentAlluxioSpaceUsed);

      // % UFS space used
      Long masterUnderfsCapacityTotal = (Long) gauges
          .get(MetricKey.CLUSTER_ROOT_UFS_CAPACITY_TOTAL.getName()).getValue();
      Long masterUnderfsCapacityUsed =
          (Long) gauges
              .get(MetricKey.CLUSTER_ROOT_UFS_CAPACITY_USED.getName()).getValue();
      int percentUfsSpaceUsed =
          (masterUnderfsCapacityTotal > 0) ? (int) (100L * masterUnderfsCapacityUsed
              / masterUnderfsCapacityTotal) : 0;
      mTimeSeriesStore.record("% UFS Space Used", percentUfsSpaceUsed);

      // Bytes read
      Long bytesReadLocalThroughput = (Long) gauges.get(
          MetricKey.CLUSTER_BYTES_READ_LOCAL_THROUGHPUT.getName()).getValue();
      Long bytesReadDomainSocketThroughput = (Long) gauges
          .get(MetricKey.CLUSTER_BYTES_READ_DOMAIN_THROUGHPUT.getName()).getValue();
      Long bytesReadRemoteThroughput = (Long) gauges
          .get(MetricKey.CLUSTER_BYTES_READ_REMOTE_THROUGHPUT.getName()).getValue();
      Long bytesReadUfsThroughput = (Long) gauges
          .get(MetricKey.CLUSTER_BYTES_READ_UFS_THROUGHPUT.getName()).getValue();
      mTimeSeriesStore.record(MetricKey.CLUSTER_BYTES_READ_LOCAL_THROUGHPUT.getName(),
          bytesReadLocalThroughput);
      mTimeSeriesStore.record(MetricKey.CLUSTER_BYTES_READ_DOMAIN_THROUGHPUT.getName(),
          bytesReadDomainSocketThroughput);
      mTimeSeriesStore.record(MetricKey.CLUSTER_BYTES_READ_REMOTE_THROUGHPUT.getName(),
          bytesReadRemoteThroughput);
      mTimeSeriesStore.record(MetricKey.CLUSTER_BYTES_READ_UFS_THROUGHPUT.getName(),
          bytesReadUfsThroughput);

      // Bytes written
      Long bytesWrittenLocalThroughput = (Long) gauges
          .get(MetricKey.CLUSTER_BYTES_WRITTEN_LOCAL_THROUGHPUT.getName())
          .getValue();
      Long bytesWrittenAlluxioThroughput = (Long) gauges
          .get(MetricKey.CLUSTER_BYTES_WRITTEN_REMOTE_THROUGHPUT.getName()).getValue();
      Long bytesWrittenDomainSocketThroughput = (Long) gauges.get(
          MetricKey.CLUSTER_BYTES_WRITTEN_DOMAIN_THROUGHPUT.getName()).getValue();
      Long bytesWrittenUfsThroughput = (Long) gauges
          .get(MetricKey.CLUSTER_BYTES_WRITTEN_UFS_THROUGHPUT.getName()).getValue();
      mTimeSeriesStore.record(MetricKey.CLUSTER_BYTES_WRITTEN_LOCAL_THROUGHPUT.getName(),
          bytesWrittenLocalThroughput);
      mTimeSeriesStore.record(MetricKey.CLUSTER_BYTES_WRITTEN_REMOTE_THROUGHPUT.getName(),
          bytesWrittenAlluxioThroughput);
      mTimeSeriesStore.record(MetricKey.CLUSTER_BYTES_WRITTEN_DOMAIN_THROUGHPUT.getName(),
          bytesWrittenDomainSocketThroughput);
      mTimeSeriesStore.record(MetricKey.CLUSTER_BYTES_WRITTEN_UFS_THROUGHPUT.getName(),
          bytesWrittenUfsThroughput);
    }

    @Override
    public void close() {} // Nothing to clean up.
  }

  private static void cleanup(UnderFileSystem ufs, String ufsPath) {
    if (!ufsPath.isEmpty()) {
      try {
        if (!ufs.deleteExistingFile(ufsPath)) {
          LOG.warn("Failed to delete UFS file {}.", ufsPath);
        }
      } catch (IOException e) {
        LOG.warn("Failed to delete UFS file {}: {}", ufsPath, e.toString());
      }
    }
  }

  @Override
  public void updateUfsMode(AlluxioURI ufsPath, UfsMode ufsMode) throws InvalidPathException,
      InvalidArgumentException, UnavailableException, AccessControlException {
    // TODO(adit): Create new fsadmin audit context
    try (RpcContext rpcContext = createRpcContext();
        FileSystemMasterAuditContext auditContext =
            createAuditContext("updateUfsMode", ufsPath, null, null)) {
      mUfsManager.setUfsMode(rpcContext, ufsPath, ufsMode);
      auditContext.setSucceeded(true);
    }
  }

  /**
   * Check if the specified operation type is allowed to the ufs.
   *
   * @param alluxioPath the Alluxio path
   * @param opType the operation type
   */
  private void checkUfsMode(AlluxioURI alluxioPath, OperationType opType)
      throws AccessControlException, InvalidPathException {
    MountTable.Resolution resolution = mMountTable.resolve(alluxioPath);
    try (CloseableResource<UnderFileSystem> ufsResource = resolution.acquireUfsResource()) {
      UnderFileSystem ufs = ufsResource.get();
      UfsMode ufsMode =
          ufs.getOperationMode(mUfsManager.getPhysicalUfsState(ufs.getPhysicalStores()));
      switch (ufsMode) {
        case NO_ACCESS:
          throw new AccessControlException(ExceptionMessage.UFS_OP_NOT_ALLOWED.getMessage(opType,
              resolution.getUri(), UfsMode.NO_ACCESS));
        case READ_ONLY:
          if (opType == OperationType.WRITE) {
            throw new AccessControlException(ExceptionMessage.UFS_OP_NOT_ALLOWED.getMessage(opType,
                resolution.getUri(), UfsMode.READ_ONLY));
          }
          break;
        default:
          // All operations are allowed
          break;
      }
    }
  }

  /**
   * The operation type. This class is used to check if an operation to the under storage is allowed
   * during maintenance.
   */
  enum OperationType {
    READ,
    WRITE,
  }

  /**
   * Class that contains metrics for FileSystemMaster.
   * This class is public because the counter names are referenced in
   * {@link alluxio.web.WebInterfaceAbstractMetricsServlet}.
   */
  public static final class Metrics {
    private static final Counter DIRECTORIES_CREATED
        = MetricsSystem.counter(MetricKey.MASTER_DIRECTORIES_CREATED.getName());
    private static final Counter FILE_BLOCK_INFOS_GOT
        = MetricsSystem.counter(MetricKey.MASTER_FILE_BLOCK_INFOS_GOT.getName());
    private static final Counter FILE_INFOS_GOT
        = MetricsSystem.counter(MetricKey.MASTER_FILE_INFOS_GOT.getName());
    private static final Counter FILES_COMPLETED
        = MetricsSystem.counter(MetricKey.MASTER_FILES_COMPLETED.getName());
    private static final Counter FILES_CREATED
        = MetricsSystem.counter(MetricKey.MASTER_FILES_CREATED.getName());
    private static final Counter FILES_FREED
        = MetricsSystem.counter(MetricKey.MASTER_FILES_FREED.getName());
    private static final Counter FILES_PERSISTED
        = MetricsSystem.counter(MetricKey.MASTER_FILES_PERSISTED.getName());
    private static final Counter NEW_BLOCKS_GOT
        = MetricsSystem.counter(MetricKey.MASTER_NEW_BLOCKS_GOT.getName());
    private static final Counter PATHS_DELETED
        = MetricsSystem.counter(MetricKey.MASTER_PATHS_DELETED.getName());
    private static final Counter PATHS_MOUNTED
        = MetricsSystem.counter(MetricKey.MASTER_PATHS_MOUNTED.getName());
    private static final Counter PATHS_RENAMED
        = MetricsSystem.counter(MetricKey.MASTER_PATHS_RENAMED.getName());
    private static final Counter PATHS_UNMOUNTED
        = MetricsSystem.counter(MetricKey.MASTER_PATHS_UNMOUNTED.getName());

    // TODO(peis): Increment the RPCs OPs at the place where we receive the RPCs.

    private static final Counter COMPLETED_OPERATION_RETRIED_COUNT
        = MetricsSystem.counter(MetricKey.MASTER_COMPLETED_OPERATION_RETRY_COUNT.getName());
    private static final Counter COMPLETE_FILE_OPS
        = MetricsSystem.counter(MetricKey.MASTER_COMPLETE_FILE_OPS.getName());
    private static final Counter CREATE_DIRECTORIES_OPS
        = MetricsSystem.counter(MetricKey.MASTER_CREATE_DIRECTORIES_OPS.getName());
    private static final Counter CREATE_FILES_OPS
        = MetricsSystem.counter(MetricKey.MASTER_CREATE_FILES_OPS.getName());
    private static final Counter DELETE_PATHS_OPS
        = MetricsSystem.counter(MetricKey.MASTER_DELETE_PATHS_OPS.getName());
    private static final Counter FREE_FILE_OPS
        = MetricsSystem.counter(MetricKey.MASTER_FREE_FILE_OPS.getName());
    private static final Counter GET_FILE_BLOCK_INFO_OPS
        = MetricsSystem.counter(MetricKey.MASTER_GET_FILE_BLOCK_INFO_OPS.getName());
    private static final Counter GET_FILE_INFO_OPS
        = MetricsSystem.counter(MetricKey.MASTER_GET_FILE_INFO_OPS.getName());
    private static final Counter GET_NEW_BLOCK_OPS
        = MetricsSystem.counter(MetricKey.MASTER_GET_NEW_BLOCK_OPS.getName());
    private static final Counter MOUNT_OPS
        = MetricsSystem.counter(MetricKey.MASTER_MOUNT_OPS.getName());
    private static final Counter RENAME_PATH_OPS
        = MetricsSystem.counter(MetricKey.MASTER_RENAME_PATH_OPS.getName());
    private static final Counter SET_ACL_OPS
        = MetricsSystem.counter(MetricKey.MASTER_SET_ACL_OPS.getName());
    private static final Counter SET_ATTRIBUTE_OPS
        = MetricsSystem.counter(MetricKey.MASTER_SET_ATTRIBUTE_OPS.getName());
    private static final Counter UNMOUNT_OPS
        = MetricsSystem.counter(MetricKey.MASTER_UNMOUNT_OPS.getName());
    public static final Counter INODE_SYNC_STREAM_COUNT
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_OPS_COUNT.getName());
    public static final Counter INODE_SYNC_STREAM_TIME_MS
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_TIME_MS.getName());
    public static final Counter INODE_SYNC_STREAM_SKIPPED
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_SKIPPED.getName());
    public static final Counter INODE_SYNC_STREAM_NO_CHANGE
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_NO_CHANGE.getName());
    public static final Counter INODE_SYNC_STREAM_SUCCESS
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_SUCCESS.getName());
    public static final Counter INODE_SYNC_STREAM_FAIL
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_FAIL.getName());
    public static final Counter INODE_SYNC_STREAM_PENDING_PATHS_TOTAL
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PENDING_PATHS.getName());
    public static final Counter INODE_SYNC_STREAM_ACTIVE_PATHS_TOTAL
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_ACTIVE_PATHS.getName());
    public static final Counter INODE_SYNC_STREAM_SYNC_PATHS_SUCCESS
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PATHS_SUCCESS.getName());
    public static final Counter INODE_SYNC_STREAM_SYNC_PATHS_FAIL
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PATHS_FAIL.getName());
    public static final Counter INODE_SYNC_STREAM_SYNC_PATHS_CANCEL
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PATHS_CANCEL.getName());
    public static final Counter METADATA_SYNC_PREFETCH_OPS_COUNT
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PREFETCH_OPS_COUNT.getName());
    public static final Counter METADATA_SYNC_PREFETCH_RETRIES
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PREFETCH_RETRIES.getName());
    public static final Counter METADATA_SYNC_PREFETCH_SUCCESS
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PREFETCH_SUCCESS.getName());
    public static final Counter METADATA_SYNC_PREFETCH_FAIL
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PREFETCH_FAIL.getName());
    public static final Counter METADATA_SYNC_PREFETCH_CANCEL
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PREFETCH_CANCEL.getName());
    public static final Counter METADATA_SYNC_PREFETCH_PATHS
        = MetricsSystem.counter(MetricKey.MASTER_METADATA_SYNC_PREFETCH_PATHS.getName());
    public static final Counter UFS_STATUS_CACHE_SIZE_TOTAL
        = MetricsSystem.counter(MetricKey.MASTER_UFS_STATUS_CACHE_SIZE.getName());
    public static final Counter UFS_STATUS_CACHE_CHILDREN_SIZE_TOTAL
        = MetricsSystem.counter(MetricKey.MASTER_UFS_STATUS_CACHE_CHILDREN_SIZE.getName());

    private static final Map<AlluxioURI, Map<UFSOps, Counter>> SAVED_UFS_OPS
        = new ConcurrentHashMap<>();

    /**
     * UFS operations enum.
     */
    public enum UFSOps {
      CREATE_FILE, GET_FILE_INFO, DELETE_FILE, LIST_STATUS
    }

    public static final Map<UFSOps, String> UFS_OPS_DESC = ImmutableMap.of(
        UFSOps.CREATE_FILE, "POST",
        UFSOps.GET_FILE_INFO, "HEAD",
        UFSOps.DELETE_FILE, "DELETE",
        UFSOps.LIST_STATUS, "LIST"
    );

    /**
     * Get operations saved per ufs counter.
     *
     * @param ufsUri ufsUri
     * @param ufsOp ufs operation
     * @return the counter object
     */
    @VisibleForTesting
    public static Counter getUfsOpsSavedCounter(AlluxioURI ufsUri, UFSOps ufsOp) {
      return SAVED_UFS_OPS.compute(ufsUri, (k, v) -> {
        if (v != null) {
          return v;
        } else {
          return new ConcurrentHashMap<>();
        }
      }).compute(ufsOp, (k, v) -> {
        if (v != null) {
          return v;
        } else {
          return MetricsSystem.counter(
              Metric.getMetricNameWithTags(UFS_OP_SAVED_PREFIX + ufsOp.name(),
              MetricInfo.TAG_UFS, MetricsSystem.escape(ufsUri)));
        }
      });
    }

    /**
     * Register some file system master related gauges.
     *
     * @param ufsManager the under filesystem manager
     * @param inodeTree the inodeTree
     */
    @VisibleForTesting
    public static void registerGauges(final UfsManager ufsManager, final InodeTree inodeTree) {
      MetricsSystem.registerGaugeIfAbsent(MetricKey.MASTER_FILES_PINNED.getName(),
          inodeTree::getPinnedSize);
      MetricsSystem.registerGaugeIfAbsent(MetricKey.MASTER_FILES_TO_PERSIST.getName(),
          () -> inodeTree.getToBePersistedIds().size());
      MetricsSystem.registerGaugeIfAbsent(MetricKey.MASTER_REPLICATION_LIMITED_FILES.getName(),
          () -> inodeTree.getReplicationLimitedFileIds().size());
      MetricsSystem.registerGaugeIfAbsent(MetricKey.MASTER_TTL_BUCKETS.getName(),
          () -> inodeTree.getTtlBuckets().getNumBuckets());
      MetricsSystem.registerGaugeIfAbsent(MetricKey.MASTER_TTL_INODES.getName(),
          () -> inodeTree.getTtlBuckets().getNumInodes());
      MetricsSystem.registerGaugeIfAbsent(MetricKey.MASTER_TOTAL_PATHS.getName(),
          inodeTree::getInodeCount);
      MetricsSystem.registerGaugeIfAbsent(MetricKey.MASTER_FILE_SIZE.getName(),
          inodeTree::getFileSizeHistogram);

      final String ufsDataFolder = Configuration.getString(
          PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS);

      MetricsSystem.registerGaugeIfAbsent(MetricKey.CLUSTER_ROOT_UFS_CAPACITY_TOTAL.getName(),
          () -> {
            try (CloseableResource<UnderFileSystem> ufsResource =
                ufsManager.getRoot().acquireUfsResource()) {
              UnderFileSystem ufs = ufsResource.get();
              return ufs.getSpace(ufsDataFolder, UnderFileSystem.SpaceType.SPACE_TOTAL);
            } catch (IOException e) {
              LOG.error(e.getMessage(), e);
              return Stream.empty();
            }
          });

      MetricsSystem.registerGaugeIfAbsent(MetricKey.CLUSTER_ROOT_UFS_CAPACITY_USED.getName(),
          () -> {
            try (CloseableResource<UnderFileSystem> ufsResource =
                ufsManager.getRoot().acquireUfsResource()) {
              UnderFileSystem ufs = ufsResource.get();
              return ufs.getSpace(ufsDataFolder, UnderFileSystem.SpaceType.SPACE_USED);
            } catch (IOException e) {
              LOG.error(e.getMessage(), e);
              return Stream.empty();
            }
          });

      MetricsSystem.registerGaugeIfAbsent(MetricKey.CLUSTER_ROOT_UFS_CAPACITY_FREE.getName(),
          () -> {
            long ret = 0L;
            try (CloseableResource<UnderFileSystem> ufsResource =
                ufsManager.getRoot().acquireUfsResource()) {
              UnderFileSystem ufs = ufsResource.get();
              ret = ufs.getSpace(ufsDataFolder, UnderFileSystem.SpaceType.SPACE_FREE);
            } catch (IOException e) {
              LOG.error(e.getMessage(), e);
            }
            return ret;
          });
    }

    private Metrics() {} // prevent instantiation
  }

  /**
   * Creates a {@link FileSystemMasterAuditContext} instance.
   *
   * @param command the command to be logged by this {@link AuditContext}
   * @param srcPath the source path of this command
   * @param dstPath the destination path of this command
   * @param srcInode the source inode of this command
   * @return newly-created {@link FileSystemMasterAuditContext} instance
   */
  protected FileSystemMasterAuditContext createAuditContext(String command, AlluxioURI srcPath,
      @Nullable AlluxioURI dstPath, @Nullable Inode srcInode) {
    // Audit log may be enabled during runtime
    AsyncUserAccessAuditLogWriter auditLogWriter = null;
    if (Configuration.getBoolean(PropertyKey.MASTER_AUDIT_LOGGING_ENABLED)) {
      auditLogWriter = mAsyncAuditLogWriter;
    }
    FileSystemMasterAuditContext auditContext =
        new FileSystemMasterAuditContext(auditLogWriter);
    if (auditLogWriter != null) {
      String user = null;
      String ugi = "";
      try {
        user = AuthenticatedClientUser.getClientUser(Configuration.global());
      } catch (AccessControlException e) {
        ugi = "N/A";
      }
      if (user != null) {
        try {
          String primaryGroup = CommonUtils.getPrimaryGroupName(user, Configuration.global());
          ugi = user + "," + primaryGroup;
        } catch (IOException e) {
          LOG.debug("Failed to get primary group for user {}.", user);
          ugi = user + ",N/A";
        }
      }
      AuthType authType =
          Configuration.getEnum(PropertyKey.SECURITY_AUTHENTICATION_TYPE, AuthType.class);
      auditContext.setUgi(ugi)
          .setAuthType(authType)
          .setIp(ClientContextServerInjector.getIpAddress())
          .setClientVersion(ClientContextServerInjector.getClientVersion())
          .setCommand(command).setSrcPath(srcPath).setDstPath(dstPath)
          .setSrcInode(srcInode).setAllowed(true)
          .setCreationTimeNs(System.nanoTime());
    }
    return auditContext;
  }

  protected BlockDeletionContext createBlockDeletionContext() {
    return new DefaultBlockDeletionContext(this::removeBlocks,
        blocks -> blocks.forEach(mUfsBlockLocationCache::invalidate));
  }

  private void removeBlocks(Collection<Long> blocks) throws IOException {
    if (blocks.isEmpty()) {
      return;
    }
    RetryPolicy retry = new CountingRetry(3);
    IOException lastThrown = null;
    while (retry.attempt()) {
      try {
        mBlockMaster.removeBlocks(blocks, true);
        return;
      } catch (UnavailableException e) {
        lastThrown = e;
      }
    }
    throw new IOException("Failed to remove deleted blocks from block master", lastThrown);
  }

  /**
   * @return a context for executing an RPC
   */
  @VisibleForTesting
  public RpcContext createRpcContext() throws UnavailableException {
    return createRpcContext(new InternalOperationContext());
  }

  /**
   * @param operationContext the operation context
   * @return a context for executing an RPC
   */
  @VisibleForTesting
  public RpcContext createRpcContext(OperationContext operationContext)
      throws UnavailableException {
    return new RpcContext(createBlockDeletionContext(), createJournalContext(true),
        operationContext.withTracker(mStateLockCallTracker));
  }

  /**
   * @param operationContext the operation context
   * @return an Rpc context that does not use a merge journal context
   */
  public RpcContext createNonMergingJournalRpcContext(OperationContext operationContext)
      throws UnavailableException {
    return new RpcContext(createBlockDeletionContext(), createJournalContext(false),
        operationContext.withTracker(mStateLockCallTracker));
  }

  private LockingScheme createLockingScheme(AlluxioURI path, FileSystemMasterCommonPOptions options,
      LockPattern desiredLockMode) throws InvalidPathException {
    return new LockingScheme(path, desiredLockMode, options,
       getSyncPathCache(), DescendantType.NONE);
  }

  protected LockingScheme createSyncLockingScheme(AlluxioURI path,
      FileSystemMasterCommonPOptions options, DescendantType descendantType)
      throws InvalidPathException {
    return new LockingScheme(path, LockPattern.READ, options,
        getSyncPathCache(), descendantType);
  }

  protected void updateAccessTime(RpcContext rpcContext, Inode inode, long opTimeMs) {
    if (mAccessTimeUpdater != null) {
      mAccessTimeUpdater.updateAccessTime(rpcContext.getJournalContext(), inode, opTimeMs);
    }
  }

  boolean isAclEnabled() {
    return Configuration.getBoolean(PropertyKey.SECURITY_AUTHORIZATION_PERMISSION_ENABLED);
  }

  @Override
  public List<TimeSeries> getTimeSeries() {
    return mTimeSeriesStore.getTimeSeries();
  }

  @Override
  public AlluxioURI reverseResolve(AlluxioURI ufsUri) throws InvalidPathException {
    MountTable.ReverseResolution resolution = mMountTable.reverseResolve(ufsUri);
    if (resolution == null) {
      throw new InvalidPathException(ufsUri.toString() + " is not a valid ufs uri");
    }
    return resolution.getUri();
  }

  @Override
  @Nullable
  public String getRootInodeOwner() {
    return mInodeTree.getRootUserName();
  }

  @Override
  public Collection<String> getStateLockSharedWaitersAndHolders() {
    return mMasterContext.getStateLockManager().getSharedWaitersAndHolders();
  }

  /**
   * @return the invalidation sync cache
   */
  @VisibleForTesting
  public UfsSyncPathCache getSyncPathCache() {
    return mMountTable.getUfsSyncPathCache();
  }

  /**
   * @return the mount table
   */
  @VisibleForTesting
  public MountTable getMountTable() {
    return mMountTable;
  }

  @Override
  public void needsSync(AlluxioURI path) throws InvalidPathException {
    getSyncPathCache().notifyInvalidation(path);
  }

  @VisibleForTesting
  protected DefaultSyncProcess createSyncProcess(
      ReadOnlyInodeStore inodeStore, MountTable mountTable,
      InodeTree inodeTree, UfsSyncPathCache syncPathCache) {
    return new DefaultSyncProcess(
        this, inodeStore, mountTable, inodeTree, syncPathCache, mUfsAbsentPathCache);
  }

  @VisibleForTesting
  DefaultSyncProcess getMetadataSyncer() {
    return mDefaultSyncProcess;
  }

  /**
   * Get scheduler.
   * @return scheduler
   */
  public Scheduler getScheduler() {
    return mScheduler;
  }
}
