package org.apache.solr.index;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.SortedDocValues;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.TestHarness;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

public class UninvertDocValuesMergePolicyTest extends SolrTestCaseJ4 {

  private static String SOLR_TESTS_SKIP_INTEGRITY_CHECK = "solr.tests.skipIntegrityCheck";
  private static String ID_FIELD = "id";
  private static String TEST_FIELD = "string_add_dv_later";

  @BeforeClass
  public static void beforeTests() throws Exception {
    System.setProperty(SOLR_TESTS_SKIP_INTEGRITY_CHECK, (random().nextBoolean() ? "true" : "false"));
  }

  @AfterClass
  public static void afterTests() {
    System.clearProperty(SOLR_TESTS_SKIP_INTEGRITY_CHECK);
  }

  @After
  public void after() throws Exception {
    deleteCore();
  }

  @Before
  public void before() throws Exception {
    initCore("solrconfig-uninvertdocvaluesmergepolicyfactory.xml", "schema-docValues.xml");
  }

  public void testIndexAndAddDocValues() throws Exception {
    Random rand = random();

    for(int i=0; i < 100; i++) {
      assertU(adoc(ID_FIELD, String.valueOf(i), TEST_FIELD, String.valueOf(i)));

      if(rand.nextBoolean()) {
        assertU(commit());
      }
    }

    assertU(commit());

    // Assert everything has been indexed and there are no docvalues
    withNewRawReader(h, topReader -> {
      assertEquals(100, topReader.numDocs());

      final FieldInfos infos = MultiFields.getMergedFieldInfos(topReader);

      // The global field type should not have docValues yet
      assertEquals(DocValuesType.NONE, infos.fieldInfo(TEST_FIELD).getDocValuesType());
    });

    addDocValuesTo(h, TEST_FIELD);

    // Add some more documents with doc values turned on including updating some
    for(int i=90; i < 110; i++) {
      assertU(adoc(ID_FIELD, String.valueOf(i), TEST_FIELD, String.valueOf(i)));

      if(rand.nextBoolean()) {
        assertU(commit());
      }
    }

    assertU(commit());

    withNewRawReader(h, topReader -> {
      assertEquals(110, topReader.numDocs());

      final FieldInfos infos = MultiFields.getMergedFieldInfos(topReader);
      // The global field type should have docValues because a document with dvs was added
      assertEquals(DocValuesType.SORTED, infos.fieldInfo(TEST_FIELD).getDocValuesType());
    });

    // Check that faceting, sorting and grouping give the correct output.
    checkFacetingGroupingSorting();

    int optimizeSegments = 1;
    assertU(optimize("maxSegments", String.valueOf(optimizeSegments)));


    // Assert all docs have the right docvalues
    withNewRawReader(h, topReader -> {
      // Assert merged into one segment
      assertEquals(110, topReader.numDocs());
      assertEquals(optimizeSegments, topReader.leaves().size());


      final FieldInfos infos = MultiFields.getMergedFieldInfos(topReader);
      // The global field type should have docValues because a document with dvs was added
      assertEquals(DocValuesType.SORTED, infos.fieldInfo(TEST_FIELD).getDocValuesType());


      // Check that all segments have the right docvalues type with the correct value
      // Also check that other fields (e.g. the id field) didn't mistakenly get docvalues added
      for (LeafReaderContext ctx : topReader.leaves()) {
        LeafReader r = ctx.reader();
        SortedDocValues docvalues = r.getSortedDocValues(TEST_FIELD);
        for(int i = 0; i < r.numDocs(); ++i) {
          Document doc = r.document(i);
          String v = doc.getField(TEST_FIELD).stringValue();
          assertEquals(DocValuesType.SORTED, r.getFieldInfos().fieldInfo(TEST_FIELD).getDocValuesType());
          assertEquals(DocValuesType.NONE, r.getFieldInfos().fieldInfo(ID_FIELD).getDocValuesType());
          assertEquals("docValues should have been added to merged segment", v, docvalues.get(i).utf8ToString());
        }
      }
    });
  }

  private void checkFacetingGroupingSorting() throws Exception {
    String response = h.query(req("q", "*:*"
        ,"facet", "true", "facet.field", TEST_FIELD
        ,"facet.limit", "-1"
        ,"sort", TEST_FIELD + " desc"
        ,"rows", "1000"
    ));

    long numDocs = 0;
    XMLResponseParser parser = new XMLResponseParser();
    try (InputStream is = new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))) {
      assertNotNull(is);
      try (Reader in = new InputStreamReader(is, StandardCharsets.UTF_8)) {
        QueryResponse qr = new QueryResponse(parser.processResponse(in), null);
        // Preserve for grouping later
        numDocs = qr.getResults().getNumFound();
        FacetField ff = qr.getFacetField(TEST_FIELD);
        int total = 0;
        for (FacetField.Count count : ff.getValues()) {
          total += count.getCount();
        }
        assertEquals("Should have exactly as many facets as docs", qr.getResults().getNumFound(), total);
        // Man, this is clumsy
        List<String> sorts = new ArrayList<>();
        for (int idx = 0; idx < qr.getResults().getNumFound(); ++idx) {
          sorts.add(String.valueOf(idx));
        }
        sorts.sort(Collections.reverseOrder());
        int idx = 0;
        for (SolrDocument doc : qr.getResults()) {
          assertEquals("Sort order should match document ID",
              doc.getFieldValue("id"), sorts.get(idx));
          ++idx;
        }
      }
    }

    // Now check grouping, it's...different.

    response = h.query(req("q", "*:*"
        ,"group", "true"
        ,"group.field", TEST_FIELD
        ,"rows", "1000"
    ));

    try (InputStream is = new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8))) {
      assertNotNull(is);
      try (Reader in = new InputStreamReader(is, StandardCharsets.UTF_8)) {
        QueryResponse qr = new QueryResponse(parser.processResponse(in), null);
        assertEquals("A bit of a weak test, but it works as long as group values are unique",
            qr.getGroupResponse().getValues().get(0).getValues().size(),
            numDocs);
      }
    }



  }
  // When an non-indexed field gets merged, it exhibit the old behavior
  // The field will be merged, docvalues headers updated, but no docvalues for this field
  public void testNonIndexedFieldDoesNonFail() throws Exception {
    // Remove Indexed from fieldType
    removeIndexFrom(h, TEST_FIELD);

    assertU(adoc(ID_FIELD, String.valueOf(1), TEST_FIELD, String.valueOf(1)));
    assertU(commit());

    addDocValuesTo(h, TEST_FIELD);

    assertU(adoc(ID_FIELD, String.valueOf(2), TEST_FIELD, String.valueOf(2)));
    assertU(commit());

    assertU(optimize("maxSegments", "1"));

    withNewRawReader(h, topReader -> {
      // Assert merged into one segment
      assertEquals(2, topReader.numDocs());
      assertEquals(1, topReader.leaves().size());


      final FieldInfos infos = MultiFields.getMergedFieldInfos(topReader);
      // The global field type should have docValues because a document with dvs was added
      assertEquals(DocValuesType.SORTED, infos.fieldInfo(TEST_FIELD).getDocValuesType());

      for (LeafReaderContext ctx : topReader.leaves()) {
        LeafReader r = ctx.reader();
        SortedDocValues docvalues = r.getSortedDocValues(TEST_FIELD);
        for(int i = 0; i < r.numDocs(); ++i) {
          Document doc = r.document(i);
          String v = doc.getField(TEST_FIELD).stringValue();
          String id = doc.getField(ID_FIELD).stringValue();
          assertEquals(DocValuesType.SORTED, r.getFieldInfos().fieldInfo(TEST_FIELD).getDocValuesType());
          assertEquals(DocValuesType.NONE, r.getFieldInfos().fieldInfo(ID_FIELD).getDocValuesType());

          if (id.equals("1")) {
            assertEquals("doc values should match, empty since field not indexed", "", docvalues.get(i).utf8ToString());
          } else {
            assertEquals("doc values should match", v, docvalues.get(i).utf8ToString());
          }
        }
      }
    });
  }


  private static void addDocValuesTo(TestHarness h, String fieldName) {
    implUpdateSchemaField(h, fieldName, (p) -> (p | 0x00008000)); // FieldProperties.DOC_VALUES
  }

  private static void removeIndexFrom(TestHarness h, String fieldName) {
    implUpdateSchemaField(h, fieldName, (p) -> (p ^ 0x00000001)); // FieldProperties.INDEXED
  }

  private static void implUpdateSchemaField(TestHarness h, String fieldName, IntUnaryOperator propertiesModifier) {
    try (SolrCore core = h.getCoreInc()) {

      // Add docvalues to the field type
      IndexSchema schema = core.getLatestSchema();
      SchemaField oldSchemaField = schema.getField(fieldName);
      SchemaField newSchemaField = new SchemaField(
          fieldName,
          oldSchemaField.getType(),
          propertiesModifier.applyAsInt(oldSchemaField.getProperties()),
          oldSchemaField.getDefaultValue());
      schema.getFields().put(fieldName, newSchemaField);
    }
  }

  private interface DirectoryReaderConsumer {
    public void accept(DirectoryReader consumer) throws Exception;
  }

  private static void withNewRawReader(TestHarness h, DirectoryReaderConsumer consumer) {
    try (SolrCore core = h.getCoreInc()) {
      final RefCounted<SolrIndexSearcher> searcherRef = core.openNewSearcher(true, true);
      final SolrIndexSearcher searcher = searcherRef.get();
      try {
        try {
          consumer.accept(searcher.getRawReader());
        } catch (Exception e) {
          fail(e.toString());
        }
      } finally {
        searcherRef.decref();
      }
    }
  }
}
