// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.core;

import com.google.firebase.firestore.model.DocumentSet;
import java.util.List;

/** A view snapshot is an immutable capture of the results of a query and the changes to them. */
public class ViewSnapshot {
  /** The possibly states a document can be in w.r.t syncing from local storage to the backend. */
  public enum SyncState {
    NONE,
    LOCAL,
    SYNCED
  }

  private final Query query;
  private final DocumentSet documents;
  private final DocumentSet oldDocuments;
  private final List<DocumentViewChange> changes;
  private final boolean isFromCache;
  private final boolean hasPendingWrites;
  private final boolean didSyncStateChange;

  public ViewSnapshot(
      Query query,
      DocumentSet documents,
      DocumentSet oldDocuments,
      List<DocumentViewChange> changes,
      boolean isFromCache,
      boolean hasPendingWrites,
      boolean didSyncStateChange) {
    this.query = query;
    this.documents = documents;
    this.oldDocuments = oldDocuments;
    this.changes = changes;
    this.isFromCache = isFromCache;
    this.hasPendingWrites = hasPendingWrites;
    this.didSyncStateChange = didSyncStateChange;
  }

  public Query getQuery() {
    return query;
  }

  public DocumentSet getDocuments() {
    return documents;
  }

  public DocumentSet getOldDocuments() {
    return oldDocuments;
  }

  public List<DocumentViewChange> getChanges() {
    return changes;
  }

  public boolean isFromCache() {
    return isFromCache;
  }

  public boolean hasPendingWrites() {
    return hasPendingWrites;
  }

  public boolean didSyncStateChange() {
    return didSyncStateChange;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ViewSnapshot that = (ViewSnapshot) o;

    if (isFromCache != that.isFromCache) {
      return false;
    }
    if (hasPendingWrites != that.hasPendingWrites) {
      return false;
    }
    if (didSyncStateChange != that.didSyncStateChange) {
      return false;
    }
    if (!query.equals(that.query)) {
      return false;
    }
    if (!documents.equals(that.documents)) {
      return false;
    }
    if (!oldDocuments.equals(that.oldDocuments)) {
      return false;
    }
    return changes.equals(that.changes);
  }

  @Override
  public int hashCode() {
    int result = query.hashCode();
    result = 31 * result + documents.hashCode();
    result = 31 * result + oldDocuments.hashCode();
    result = 31 * result + changes.hashCode();
    result = 31 * result + (isFromCache ? 1 : 0);
    result = 31 * result + (hasPendingWrites ? 1 : 0);
    result = 31 * result + (didSyncStateChange ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return "ViewSnapshot("
        + query
        + ", "
        + documents
        + ", "
        + oldDocuments
        + ", "
        + changes
        + ", isFromCache="
        + isFromCache
        + ", hasPendingWrites="
        + hasPendingWrites
        + ", didSyncStateChange="
        + didSyncStateChange
        + ")";
  }
}
