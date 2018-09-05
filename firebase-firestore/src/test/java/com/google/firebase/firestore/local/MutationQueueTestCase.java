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

package com.google.firebase.firestore.local;

import static com.google.common.truth.Truth.assertThat;
import static com.google.firebase.firestore.testutil.TestUtil.key;
import static com.google.firebase.firestore.testutil.TestUtil.map;
import static com.google.firebase.firestore.testutil.TestUtil.patchMutation;
import static com.google.firebase.firestore.testutil.TestUtil.path;
import static com.google.firebase.firestore.testutil.TestUtil.setMutation;
import static com.google.firebase.firestore.testutil.TestUtil.streamToken;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.firebase.Timestamp;
import com.google.firebase.database.collection.ImmutableSortedSet;
import com.google.firebase.firestore.auth.User;
import com.google.firebase.firestore.core.Query;
import com.google.firebase.firestore.model.DocumentKey;
import com.google.firebase.firestore.model.mutation.Mutation;
import com.google.firebase.firestore.model.mutation.MutationBatch;
import com.google.firebase.firestore.model.mutation.SetMutation;
import com.google.firebase.firestore.remote.WriteStream;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * These are tests for any implementation of the MutationQueue interface.
 *
 * <p>To test a specific implementation of MutationQueue:
 *
 * <ol>
 *   <li>Subclass MutationQueueTestCase.
 *   <li>Override {@link #getPersistence}, creating a new implementation of Persistence.
 * </ol>
 */
public abstract class MutationQueueTestCase {

  @Rule public TestName name = new TestName();

  private Persistence persistence;
  private MutationQueue mutationQueue;

  @Before
  public void setUp() {
    persistence = getPersistence();
    mutationQueue = persistence.getMutationQueue(User.UNAUTHENTICATED);
    mutationQueue.start();
  }

  @After
  public void tearDown() {
    persistence.shutdown();
  }

  abstract Persistence getPersistence();

  @Test
  public void testCountBatches() {
    assertEquals(0, batchCount());
    assertTrue(mutationQueue.isEmpty());

    MutationBatch batch1 = addMutationBatch();
    assertEquals(1, batchCount());
    assertFalse(mutationQueue.isEmpty());

    MutationBatch batch2 = addMutationBatch();
    assertEquals(2, batchCount());
    assertFalse(mutationQueue.isEmpty());

    removeMutationBatches(batch2);
    assertEquals(1, batchCount());

    removeMutationBatches(batch1);
    assertEquals(0, batchCount());
    assertTrue(mutationQueue.isEmpty());
  }

  @Test
  public void testAcknowledgeBatchId() {
    // Initial state of an empty queue
    assertEquals(MutationBatch.UNKNOWN, mutationQueue.getHighestAcknowledgedBatchId());

    // Adding mutation batches should not change the highest acked batchId.
    MutationBatch batch1 = addMutationBatch();
    MutationBatch batch2 = addMutationBatch();
    MutationBatch batch3 = addMutationBatch();
    assertThat(batch1.getBatchId(), greaterThan(MutationBatch.UNKNOWN));
    assertThat(batch2.getBatchId(), greaterThan(batch1.getBatchId()));
    assertThat(batch3.getBatchId(), greaterThan(batch2.getBatchId()));

    assertEquals(MutationBatch.UNKNOWN, mutationQueue.getHighestAcknowledgedBatchId());

    acknowledgeBatch(batch1);
    assertEquals(batch1.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());

    acknowledgeBatch(batch2);
    assertEquals(batch2.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());

    removeMutationBatches(batch1);
    assertEquals(batch2.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());

    removeMutationBatches(batch2);
    assertEquals(batch2.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());

    // Batch 3 never acknowledged.
    removeMutationBatches(batch3);
    assertEquals(batch2.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());
  }

  @Test
  public void testAcknowledgeThenRemove() {
    MutationBatch batch1 = addMutationBatch();

    persistence.runTransaction(
        name.getMethodName(),
        () -> {
          mutationQueue.acknowledgeBatch(batch1, WriteStream.EMPTY_STREAM_TOKEN);
          mutationQueue.removeMutationBatches(asList(batch1));
        });

    assertEquals(0, batchCount());
    assertEquals(batch1.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());
  }

  @Test
  public void testHighestAcknowledgedBatchIdNeverExceedsNextBatchId() {
    MutationBatch batch1 = addMutationBatch();
    MutationBatch batch2 = addMutationBatch();
    acknowledgeBatch(batch1);
    acknowledgeBatch(batch2);
    assertEquals(batch2.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());

    removeMutationBatches(batch1, batch2);
    assertEquals(batch2.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());

    // Restart the queue so that nextBatchId will be reset.
    mutationQueue = persistence.getMutationQueue(User.UNAUTHENTICATED);
    mutationQueue.start();

    persistence.runTransaction("Start mutationQueue", () -> mutationQueue.start());

    // Verify that on restart with an empty queue, nextBatchId falls to a lower value.
    assertThat(mutationQueue.getNextBatchId(), lessThan(batch2.getBatchId()));

    // As a result highestAcknowledgedBatchId must also reset lower.
    assertEquals(MutationBatch.UNKNOWN, mutationQueue.getHighestAcknowledgedBatchId());

    // The mutation queue will reset the next batchId after all mutations are removed so adding
    // another mutation will cause a collision.
    MutationBatch newBatch = addMutationBatch();
    assertEquals(batch1.getBatchId(), newBatch.getBatchId());

    // Restart the queue with one unacknowledged batch in it.
    persistence.runTransaction("Start mutationQueue", () -> mutationQueue.start());

    assertEquals(newBatch.getBatchId() + 1, mutationQueue.getNextBatchId());

    // highestAcknowledgedBatchId must still be MutationBatch.UNKNOWN.
    assertEquals(MutationBatch.UNKNOWN, mutationQueue.getHighestAcknowledgedBatchId());
  }

  @Test
  public void testLookupMutationBatch() {
    // Searching on an empty queue should not find a non-existent batch
    MutationBatch notFound = mutationQueue.lookupMutationBatch(42);
    assertNull(notFound);

    List<MutationBatch> batches = createBatches(10);
    List<MutationBatch> removed = makeHoles(asList(2, 6, 7), batches);

    // After removing, a batch should not be found
    for (MutationBatch batch : removed) {
      notFound = mutationQueue.lookupMutationBatch(batch.getBatchId());
      assertNull(notFound);
    }

    // Remaining entries should still be found
    for (MutationBatch batch : batches) {
      MutationBatch found = mutationQueue.lookupMutationBatch(batch.getBatchId());
      assertNotNull(found);
      assertEquals(batch.getBatchId(), found.getBatchId());
    }

    // Even on a nonempty queue searching should not find a non-existent batch
    notFound = mutationQueue.lookupMutationBatch(42);
    assertNull(notFound);
  }

  @Test
  public void testNextMutationBatchAfterBatchId() {
    List<MutationBatch> batches = createBatches(10);

    // This is an array of successors assuming the removals below will happen:
    List<MutationBatch> afters = asList(batches.get(3), batches.get(8), batches.get(8));
    List<MutationBatch> removed = makeHoles(asList(2, 6, 7), batches);

    for (int i = 0; i < batches.size() - 1; i++) {
      MutationBatch current = batches.get(i);
      MutationBatch next = batches.get(i + 1);
      MutationBatch found = mutationQueue.getNextMutationBatchAfterBatchId(current.getBatchId());
      assertNotNull(found);
      assertEquals(next.getBatchId(), found.getBatchId());
    }

    for (int i = 0; i < removed.size(); i++) {
      MutationBatch current = removed.get(i);
      MutationBatch next = afters.get(i);
      MutationBatch found = mutationQueue.getNextMutationBatchAfterBatchId(current.getBatchId());
      assertNotNull(found);
      assertEquals(next.getBatchId(), found.getBatchId());
    }

    MutationBatch first = batches.get(0);
    MutationBatch found = mutationQueue.getNextMutationBatchAfterBatchId(first.getBatchId() - 42);
    assertNotNull(found);
    assertEquals(first.getBatchId(), found.getBatchId());

    MutationBatch last = batches.get(batches.size() - 1);
    MutationBatch notFound = mutationQueue.getNextMutationBatchAfterBatchId(last.getBatchId());
    assertNull(notFound);
  }

  @Test
  public void testNextMutationBatchAfterBatchIdSkipsAcknowledgedBatches() {
    List<MutationBatch> batches = createBatches(3);
    assertEquals(
        batches.get(0), mutationQueue.getNextMutationBatchAfterBatchId(MutationBatch.UNKNOWN));

    acknowledgeBatch(batches.get(0));
    assertEquals(
        batches.get(1), mutationQueue.getNextMutationBatchAfterBatchId(MutationBatch.UNKNOWN));
    assertEquals(
        batches.get(1),
        mutationQueue.getNextMutationBatchAfterBatchId(batches.get(0).getBatchId()));
    assertEquals(
        batches.get(2),
        mutationQueue.getNextMutationBatchAfterBatchId(batches.get(1).getBatchId()));
  }

  @Test
  public void testAllMutationBatchesThroughBatchID() {
    List<MutationBatch> batches = createBatches(10);
    makeHoles(asList(2, 6, 7), batches);

    List<MutationBatch> found;
    List<MutationBatch> expected;

    found = mutationQueue.getAllMutationBatchesThroughBatchId(batches.get(0).getBatchId() - 1);
    assertEquals(emptyList(), found);

    for (int i = 0; i < batches.size(); i++) {
      found = mutationQueue.getAllMutationBatchesThroughBatchId(batches.get(i).getBatchId());
      expected = batches.subList(0, i + 1);
      assertEquals(expected, found);
    }
  }

  @Test
  public void testAllMutationBatchesAffectingDocumentKey() {
    List<Mutation> mutations =
        asList(
            setMutation("fob/bar", map("a", 1)),
            setMutation("foo/bar", map("a", 1)),
            patchMutation("foo/bar", map("b", 1)),
            setMutation("foo/bar/suffix/key", map("a", 1)),
            setMutation("foo/baz", map("a", 1)),
            setMutation("food/bar", map("a", 1)));

    // Store all the mutations.
    List<MutationBatch> batches = new ArrayList<>();
    persistence.runTransaction(
        "New mutation batch",
        () -> {
          for (Mutation mutation : mutations) {
            batches.add(mutationQueue.addMutationBatch(Timestamp.now(), asList(mutation)));
          }
        });

    List<MutationBatch> expected = asList(batches.get(1), batches.get(2));
    List<MutationBatch> matches =
        mutationQueue.getAllMutationBatchesAffectingDocumentKey(key("foo/bar"));

    assertEquals(expected, matches);
  }

  @Test
  public void testAllMutationBatchesAffectingDocumentKeys() {
    List<Mutation> mutations =
        asList(
            setMutation("fob/bar", map("a", 1)),
            setMutation("foo/bar", map("a", 1)),
            patchMutation("foo/bar", map("b", 1)),
            setMutation("foo/bar/suffix/key", map("a", 1)),
            setMutation("foo/baz", map("a", 1)),
            setMutation("food/bar", map("a", 1)));

    // Store all the mutations.
    List<MutationBatch> batches = new ArrayList<>();
    persistence.runTransaction(
        "New mutation batch",
        () -> {
          for (Mutation mutation : mutations) {
            batches.add(mutationQueue.addMutationBatch(Timestamp.now(), asList(mutation)));
          }
        });

    ImmutableSortedSet<DocumentKey> keys =
        DocumentKey.emptyKeySet().insert(key("foo/bar")).insert(key("foo/baz"));

    List<MutationBatch> expected = asList(batches.get(1), batches.get(2), batches.get(4));
    List<MutationBatch> matches = mutationQueue.getAllMutationBatchesAffectingDocumentKeys(keys);

    assertEquals(expected, matches);
  }

  // PORTING NOTE: this test only applies to Android, because it's the only platform where the
  // implementation of getAllMutationBatchesAffectingDocumentKeys might split the input into several
  // queries.
  @Test
  public void testAllMutationBatchesAffectingDocumentLotsOfDocumentKeys() {
    List<Mutation> mutations = new ArrayList<>();
    // Make sure to force SQLite implementation to split the large query into several smaller ones.
    int lotsOfMutations = 2000;
    for (int i = 0; i < lotsOfMutations; i++) {
      mutations.add(setMutation("foo/" + i, map("a", 1)));
    }
    List<MutationBatch> batches = new ArrayList<>();
    persistence.runTransaction(
        "New mutation batch",
        () -> {
          for (Mutation mutation : mutations) {
            batches.add(mutationQueue.addMutationBatch(Timestamp.now(), asList(mutation)));
          }
        });

    // To make it easier validating the large resulting set, use a simple criteria to evaluate --
    // query all keys with an even number in them and make sure the corresponding batches make it
    // into the results.
    ImmutableSortedSet<DocumentKey> evenKeys = DocumentKey.emptyKeySet();
    List<MutationBatch> expected = new ArrayList<>();
    for (int i = 2; i < lotsOfMutations; i += 2) {
      evenKeys = evenKeys.insert(key("foo/" + i));
      expected.add(batches.get(i));
    }

    List<MutationBatch> matches =
        mutationQueue.getAllMutationBatchesAffectingDocumentKeys(evenKeys);
    assertThat(matches).containsExactlyElementsIn(expected).inOrder();
  }

  @Test
  public void testAllMutationBatchesAffectingQuery() {
    List<Mutation> mutations =
        asList(
            setMutation("fob/bar", map("a", 1)),
            setMutation("foo/bar", map("a", 1)),
            patchMutation("foo/bar", map("b", 1)),
            setMutation("foo/bar/suffix/key", map("a", 1)),
            setMutation("foo/baz", map("a", 1)),
            setMutation("food/bar", map("a", 1)));

    // Store all the mutations.
    List<MutationBatch> batches = new ArrayList<>();
    persistence.runTransaction(
        "New mutation batch",
        () -> {
          for (Mutation mutation : mutations) {
            batches.add(mutationQueue.addMutationBatch(Timestamp.now(), asList(mutation)));
          }
        });

    List<MutationBatch> expected = asList(batches.get(1), batches.get(2), batches.get(4));

    Query query = Query.atPath(path("foo"));
    List<MutationBatch> matches = mutationQueue.getAllMutationBatchesAffectingQuery(query);

    assertEquals(expected, matches);
  }

  @Test
  public void testAllMutationBatchesAffectingQuery_withCompoundBatches() {
    Map<String, Object> value = map("a", 1);

    // Store all the mutations.
    List<MutationBatch> batches = new ArrayList<>();
    persistence.runTransaction(
        "New mutation batch",
        () -> {
          batches.add(
              mutationQueue.addMutationBatch(
                  Timestamp.now(),
                  asList(setMutation("foo/bar", value), setMutation("foo/bar/baz/quux", value))));
          batches.add(
              mutationQueue.addMutationBatch(
                  Timestamp.now(),
                  asList(setMutation("foo/bar", value), setMutation("foo/baz", value))));
        });

    List<MutationBatch> expected = asList(batches.get(0), batches.get(1));

    Query query = Query.atPath(path("foo"));
    List<MutationBatch> matches = mutationQueue.getAllMutationBatchesAffectingQuery(query);

    assertEquals(expected, matches);
  }

  @Test
  public void testRemoveMutationBatches() {
    List<MutationBatch> batches = createBatches(10);
    MutationBatch last = batches.get(batches.size() - 1);

    removeMutationBatches(batches.remove(0));
    assertEquals(9, batchCount());

    List<MutationBatch> found;

    found = mutationQueue.getAllMutationBatchesThroughBatchId(last.getBatchId());
    assertEquals(batches, found);
    assertEquals(9, found.size());

    removeMutationBatches(batches.get(0), batches.get(1), batches.get(2));
    batches.remove(batches.get(0));
    batches.remove(batches.get(0));
    batches.remove(batches.get(0));
    assertEquals(6, batchCount());

    found = mutationQueue.getAllMutationBatchesThroughBatchId(last.getBatchId());
    assertEquals(batches, found);
    assertEquals(6, found.size());

    removeMutationBatches(batches.remove(batches.size() - 1));
    assertEquals(5, batchCount());

    found = mutationQueue.getAllMutationBatchesThroughBatchId(last.getBatchId());
    assertEquals(batches, found);
    assertEquals(5, found.size());

    removeMutationBatches(batches.remove(3));
    assertEquals(4, batchCount());

    removeMutationBatches(batches.remove(1));
    assertEquals(3, batchCount());

    found = mutationQueue.getAllMutationBatchesThroughBatchId(last.getBatchId());
    assertEquals(batches, found);
    assertEquals(3, found.size());

    removeMutationBatches(batches.toArray(new MutationBatch[0]));
    found = mutationQueue.getAllMutationBatchesThroughBatchId(last.getBatchId());
    assertEquals(emptyList(), found);
    assertEquals(0, found.size());
    assertTrue(mutationQueue.isEmpty());
  }

  @Test
  public void testStreamToken() {
    ByteString streamToken1 = streamToken("token1");
    ByteString streamToken2 = streamToken("token2");

    persistence.runTransaction(
        "initial stream token", () -> mutationQueue.setLastStreamToken(streamToken1));

    MutationBatch batch1 = addMutationBatch();
    addMutationBatch();

    assertEquals(streamToken1, mutationQueue.getLastStreamToken());

    persistence.runTransaction(
        "acknowledgeBatchId", () -> mutationQueue.acknowledgeBatch(batch1, streamToken2));

    assertEquals(batch1.getBatchId(), mutationQueue.getHighestAcknowledgedBatchId());
    assertEquals(streamToken2, mutationQueue.getLastStreamToken());
  }

  /** Creates a new MutationBatch with the next batch ID and a set of dummy mutations. */
  private MutationBatch addMutationBatch() {
    return addMutationBatch("foo/bar");
  }

  /**
   * Creates a new MutationBatch with the given key, the next batch ID and a set of dummy mutations.
   */
  private MutationBatch addMutationBatch(String key) {
    SetMutation mutation = setMutation(key, map("a", 1));

    return persistence.runTransaction(
        "New mutation batch",
        () -> mutationQueue.addMutationBatch(Timestamp.now(), asList(mutation)));
  }

  /**
   * Creates a list of batches containing <tt>number</tt> dummy MutationBatches. Each has a
   * different batchId.
   */
  private List<MutationBatch> createBatches(int number) {
    List<MutationBatch> batches = new ArrayList<>(number);
    for (int i = 0; i < number; i++) {
      batches.add(addMutationBatch());
    }
    return batches;
  }

  private void acknowledgeBatch(MutationBatch batch) {
    persistence.runTransaction(
        "Ack batchId", () -> mutationQueue.acknowledgeBatch(batch, WriteStream.EMPTY_STREAM_TOKEN));
  }

  /** Calls removeMutationBatches on the mutation queue in a new transaction and commits. */
  private void removeMutationBatches(MutationBatch... batches) {
    persistence.runTransaction(
        "Remove mutation batches", () -> mutationQueue.removeMutationBatches(asList(batches)));
  }

  /** Returns the number of mutation batches in the mutation queue. */
  private int batchCount() {
    return mutationQueue.getAllMutationBatches().size();
  }

  /**
   * Removes entries from from the given <tt>batches</tt> and returns them.
   *
   * @param holes An list of indexes in the batches list; in increasing order. Indexes are relative
   *     to the original state of the batches list, not any intermediate state that might occur.
   * @param batches The list to mutate, removing entries from it.
   * @return A new list containing all the entries that were removed from @a batches.
   */
  private List<MutationBatch> makeHoles(List<Integer> holes, List<MutationBatch> batches) {
    List<MutationBatch> removed = new ArrayList<>();
    for (int i = 0; i < holes.size(); i++) {
      int index = holes.get(i) - i;
      MutationBatch batch = batches.get(index);
      removeMutationBatches(batch);

      batches.remove(index);
      removed.add(batch);
    }
    return removed;
  }
}
