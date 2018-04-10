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
package org.apache.accumulo.core.client.impl;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.impl.Bulk.FileInfo;
import org.apache.accumulo.core.client.impl.Bulk.Files;
import org.apache.accumulo.core.client.impl.BulkSerialize.Input;
import org.apache.accumulo.core.client.impl.BulkSerialize.LoadMappingIterator;
import org.apache.accumulo.core.client.impl.Table.ID;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.hadoop.io.Text;
import org.junit.Assert;
import org.junit.Test;

public class BulkSerializeTest {

  @Test
  public void writeReadLoadMapping() throws Exception {
    Table.ID tableId = Table.ID.of("3");
    SortedMap<KeyExtent,Bulk.Files> mapping = generateMapping(tableId);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    BulkSerialize.writeLoadMapping(mapping, "/some/dir", tableId, "table3", p -> baos);

    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

    try (LoadMappingIterator lmi = BulkSerialize.readLoadMapping("/some/dir", tableId, p -> bais)) {
      SortedMap<KeyExtent,Bulk.Files> readMapping = new TreeMap<>();
      lmi.forEachRemaining(e -> readMapping.put(e.getKey(), e.getValue()));
      Assert.assertEquals(mapping, readMapping);
    }
  }

  @Test
  public void writeReadRenames() throws Exception {

    Map<String,String> renames = new HashMap<>();
    for (String f : "f1 f2 f3 f4 f5".split(" "))
      renames.put("old_" + f + ".rf", "new_" + f + ".rf");
    Table.ID tableId = Table.ID.of("3");

    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    BulkSerialize.writeRenameMap(renames, "/some/dir", tableId, p -> baos);

    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

    Map<String,String> readMap = BulkSerialize.readRenameMap("/some/dir", tableId, p -> bais);

    assertEquals("Read renames file wrong size", renames.size(), readMap.size());
    assertEquals("Read renames file different from what was written.", renames, readMap);
  }

  @Test
  public void testRemap() throws Exception {
    Table.ID tableId = Table.ID.of("3");
    SortedMap<KeyExtent,Bulk.Files> mapping = generateMapping(tableId);

    SortedMap<KeyExtent,Bulk.Files> newNameMapping = new TreeMap<>();

    Map<String,String> nameMap = new HashMap<>();

    mapping.forEach((extent, files) -> {
      Files newFiles = new Files();
      files.forEach(fi -> {
        newFiles.add(new FileInfo("N" + fi.name, fi.estSize, fi.estEntries));
        nameMap.put(fi.name, "N" + fi.name);
      });

      newNameMapping.put(extent, newFiles);
    });

    ByteArrayOutputStream mappingBaos = new ByteArrayOutputStream();
    ByteArrayOutputStream nameBaos = new ByteArrayOutputStream();

    BulkSerialize.writeRenameMap(nameMap, "/some/dir", tableId, p -> nameBaos);
    BulkSerialize.writeLoadMapping(mapping, "/some/dir", tableId, "table3", p -> mappingBaos);

    Input input = p -> {
      if (p.getName().equals(Constants.BULK_LOAD_MAPPING)) {
        return new ByteArrayInputStream(mappingBaos.toByteArray());
      } else if (p.getName().equals(Constants.BULK_RENAME_FILE)) {
        return new ByteArrayInputStream(nameBaos.toByteArray());
      } else {
        throw new IllegalArgumentException("bad path " + p);
      }
    };

    try (LoadMappingIterator lmi = BulkSerialize.getUpdatedLoadMapping("/some/dir", tableId,
        input)) {
      SortedMap<KeyExtent,Bulk.Files> actual = new TreeMap<>();
      lmi.forEachRemaining(e -> actual.put(e.getKey(), e.getValue()));
      Assert.assertEquals(newNameMapping, actual);
    }

  }

  public SortedMap<KeyExtent,Bulk.Files> generateMapping(ID tableId) {
    SortedMap<KeyExtent,Bulk.Files> mapping = new TreeMap<>();
    Bulk.Files testFiles = new Bulk.Files();
    Bulk.Files testFiles2 = new Bulk.Files();
    Bulk.Files testFiles3 = new Bulk.Files();
    long c = 0L;
    for (String f : "f1 f2 f3".split(" ")) {
      c++;
      testFiles.add(new Bulk.FileInfo(f, c, c));
    }
    c = 0L;
    for (String f : "g1 g2 g3".split(" ")) {
      c++;
      testFiles2.add(new Bulk.FileInfo(f, c, c));
    }
    for (String f : "h1 h2 h3".split(" ")) {
      c++;
      testFiles3.add(new Bulk.FileInfo(f, c, c));
    }

    // add out of order to test sorting
    mapping.put(new KeyExtent(tableId, new Text("d"), new Text("c")), testFiles);
    mapping.put(new KeyExtent(tableId, new Text("c"), new Text("b")), testFiles2);
    mapping.put(new KeyExtent(tableId, new Text("b"), new Text("a")), testFiles3);

    return mapping;
  }
}
