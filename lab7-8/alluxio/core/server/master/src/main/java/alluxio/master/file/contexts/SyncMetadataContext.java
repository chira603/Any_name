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

package alluxio.master.file.contexts;

import alluxio.conf.Configuration;
import alluxio.grpc.ExistsPOptions;
import alluxio.grpc.SyncMetadataPOptions;
import alluxio.util.FileSystemOptionsUtils;

import com.google.common.base.MoreObjects;

/**
 * Used to merge and wrap {@link SyncMetadataPOptions}.
 */
public class SyncMetadataContext
    extends OperationContext<SyncMetadataPOptions.Builder, SyncMetadataContext> {

  /**
   * Creates context with given option data.
   *
   * @param optionsBuilder options builder
   */
  private SyncMetadataContext(SyncMetadataPOptions.Builder optionsBuilder) {
    super(optionsBuilder);
  }

  /**
   * @param optionsBuilder Builder for proto {@link SyncMetadataPOptions}
   * @return the instance of {@link SyncMetadataContext} with given options
   */
  public static SyncMetadataContext create(SyncMetadataPOptions.Builder optionsBuilder) {
    return new SyncMetadataContext(optionsBuilder);
  }

  /**
   * Merges and embeds the given {@link ExistsPOptions} with the corresponding master
   * options.
   *
   * @param optionsBuilder Builder for proto {@link ExistsPOptions} to merge with defaults
   * @return the instance of {@link SyncMetadataContext} with default values for master
   */
  public static SyncMetadataContext mergeFrom(SyncMetadataPOptions.Builder optionsBuilder) {
    SyncMetadataPOptions masterOptions =
        FileSystemOptionsUtils.syncMetadataDefaults(Configuration.global());
    SyncMetadataPOptions.Builder mergedOptionsBuilder =
        masterOptions.toBuilder().mergeFrom(optionsBuilder.build());
    return create(mergedOptionsBuilder);
  }

  /**
   * @return the instance of {@link SyncMetadataContext} with default values for master
   */
  public static SyncMetadataContext defaults() {
    return create(FileSystemOptionsUtils
        .syncMetadataDefaults(Configuration.global()).toBuilder());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("ProtoOptions", getOptions().build())
        .toString();
  }
}
