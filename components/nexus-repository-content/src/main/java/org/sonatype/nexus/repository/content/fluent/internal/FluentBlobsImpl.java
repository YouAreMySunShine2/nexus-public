/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.content.fluent.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.common.hash.MultiHashingInputStream;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentBlobs;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.repository.view.payloads.TempBlobPartPayload;
import org.sonatype.nexus.security.ClientInfo;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.hash.HashCode;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.ofNullable;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_IP_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.REPO_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.TEMPORARY_BLOB_HEADER;
import static org.sonatype.nexus.repository.view.ContentTypes.APPLICATION_OCTET_STREAM;

/**
 * {@link FluentBlobs} implementation.
 *
 * @since 3.24
 */
public class FluentBlobsImpl
    implements FluentBlobs
{
  private static final String SYSTEM = "system";

  private final ContentFacetSupport facet;

  private final BlobStore blobStore;

  public FluentBlobsImpl(final ContentFacetSupport facet, final BlobStore blobStore) {
    this.facet = checkNotNull(facet);
    this.blobStore = checkNotNull(blobStore);
  }

  @Override
  public BlobStoreMetrics getMetrics() {
    return blobStore.getMetrics();
  }

  @Override
  public TempBlob ingest(final InputStream in,
                         @Nullable final String contentType,
                         final Iterable<HashAlgorithm> hashing) {
    return ingest(in, contentType, ImmutableMap.of(), hashing);
  }

  @Override
  public TempBlob ingest(final InputStream in,
                         @Nullable final String contentType,
                         final Map<String, String> headers,
                         final Iterable<HashAlgorithm> hashing)
  {
    Optional<ClientInfo> clientInfo = facet.clientInfo();

    Builder<String, String> tempHeaders = ImmutableMap.builder();
    tempHeaders.putAll(headers);

    maybePut(tempHeaders, headers, TEMPORARY_BLOB_HEADER, "");
    maybePut(tempHeaders, headers, REPO_NAME_HEADER, facet.repository().getName());
    maybePut(tempHeaders, headers, BLOB_NAME_HEADER, "temp");
    maybePut(tempHeaders, headers, CREATED_BY_HEADER, clientInfo.map(ClientInfo::getUserid).orElse(SYSTEM));
    maybePut(tempHeaders, headers, CREATED_BY_IP_HEADER, clientInfo.map(ClientInfo::getRemoteIP).orElse(SYSTEM));
    maybePut(tempHeaders, headers, CONTENT_TYPE_HEADER, ofNullable(contentType).orElse(APPLICATION_OCTET_STREAM));

    MultiHashingInputStream hashingStream = new MultiHashingInputStream(hashing, in);
    Blob blob = blobStore.create(hashingStream, tempHeaders.build());

    return new TempBlob(blob, hashingStream.hashes(), true, blobStore);
  }

  @Override
  public TempBlob ingest(final Payload payload, final Iterable<HashAlgorithm> hashing) {
    if (payload instanceof TempBlobPartPayload) {
      return ((TempBlobPartPayload) payload).getTempBlob();
    }
    try (InputStream in = payload.openInputStream()) {
      return ingest(in, cleanupContentType(payload.getContentType()), hashing);
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * We often use Content-Type and MIME type interchangeably throughout NXRM.
   * https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Type
   * This method transforms the content type into the mime type that we expect
   * @param contentType
   * @return just the mime type, which is what we mean when we say content type
   */
  private String cleanupContentType(final String contentType) {
    if (contentType == null) {
      return null;
    }

    int semicolonIndex = contentType.indexOf(';');
    if (semicolonIndex == -1) {
      return contentType;
    } else {
      return contentType.substring(0, semicolonIndex);
    }
  }

  @Override
  public Blob ingest(
      final Path sourceFile,
      final Map<String, String> headers,
      final HashCode sha1,
      final long size)
  {
    Optional<ClientInfo> clientInfo = facet.clientInfo();

    Builder<String, String> newHeaders = ImmutableMap.builder();
    newHeaders.putAll(headers);
    // maybe add additional headers
    maybePut(newHeaders, headers, REPO_NAME_HEADER, facet.repository().getName());
    maybePut(newHeaders, headers, CREATED_BY_HEADER, clientInfo.map(ClientInfo::getUserid).orElse(SYSTEM));
    maybePut(newHeaders, headers, CREATED_BY_IP_HEADER, clientInfo.map(ClientInfo::getRemoteIP).orElse(SYSTEM));

    return blobStore.create(sourceFile, newHeaders.build(), size, sha1);
  }

  @Override
  public Optional<Blob> blob(final BlobRef blobRef) {
    return ofNullable(blobStore.get(blobRef.getBlobId()));
  }

  private void maybePut(
      final ImmutableMap.Builder<String, String> builder,
      final Map<String, String> existing,
      final String key,
      final String value)
  {
    if (!existing.containsKey(key)) {
      builder.put(key, value);
    }
  }
}
