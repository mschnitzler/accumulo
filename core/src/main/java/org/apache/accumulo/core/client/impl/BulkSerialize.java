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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.impl.Bulk.Files;
import org.apache.accumulo.core.client.impl.Bulk.Mapping;
import org.apache.accumulo.core.client.impl.Table.ID;
import org.apache.accumulo.core.client.impl.thrift.TableOperation;
import org.apache.accumulo.core.client.impl.thrift.TableOperationExceptionType;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.hadoop.fs.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Place for all bulk import serialization
 */
public class BulkSerialize {

  private static class ByteArrayToBase64TypeAdapter
      implements JsonSerializer<byte[]>, JsonDeserializer<byte[]> {

    Decoder decoder = Base64.getUrlDecoder();
    Encoder encoder = Base64.getUrlEncoder();

    @Override
    public byte[] deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
        throws JsonParseException {
      return decoder.decode(json.getAsString());
    }

    @Override
    public JsonElement serialize(byte[] src, Type typeOfSrc, JsonSerializationContext context) {
      return new JsonPrimitive(encoder.encodeToString(src));
    }
  }

  private static Gson createGson() {
    return new GsonBuilder()
        .registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter()).create();
  }

  public static interface Output {
    OutputStream create(Path path) throws IOException;
  }

  public static interface Input {
    InputStream open(Path path) throws IOException;
  }

  /**
   * Convert the SortedMap to a SortedSet of Json friendly Bulk.Mapping objects and serialize to
   * json
   */
  public static void writeLoadMapping(SortedMap<KeyExtent,Bulk.Files> loadMapping, String sourceDir,
      Table.ID tableId, String tableName, Output fs) throws IOException {
    final Path lmFile = new Path(sourceDir, Constants.BULK_LOAD_MAPPING);

    try (OutputStream fsOut = fs.create(lmFile);
        JsonWriter writer = new JsonWriter(
            new BufferedWriter(new OutputStreamWriter(fsOut, UTF_8)))) {
      Gson gson = createGson();
      writer.setIndent("  ");
      writer.beginArray();
      Set<Entry<KeyExtent,Files>> es = loadMapping.entrySet();
      for (Entry<KeyExtent,Files> entry : es) {
        Mapping mapping = new Bulk.Mapping(entry.getKey(), entry.getValue());
        gson.toJson(mapping, Mapping.class, writer);
      }
      writer.endArray();
    }
  }

  public static class LoadMappingIterator
      implements Iterator<Entry<KeyExtent,Bulk.Files>>, AutoCloseable {
    private ID tableId;
    private JsonReader reader;
    private Gson gson = createGson();
    private Map<String,String> renameMap;

    private LoadMappingIterator(Table.ID tableId, JsonReader reader) {
      this.tableId = tableId;
      this.reader = reader;
    }

    private void setRenameMap(Map<String,String> renameMap) {
      this.renameMap = renameMap;
    }

    @Override
    public void close() throws Exception {
      reader.close();
    }

    @Override
    public boolean hasNext() {
      try {
        return reader.hasNext();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public Entry<KeyExtent,Files> next() {
      Bulk.Mapping bm = gson.fromJson(reader, Bulk.Mapping.class);
      if (renameMap != null) {
        return new AbstractMap.SimpleEntry<>(bm.getKeyExtent(tableId),
            bm.getFiles().mapNames(renameMap));
      } else {
        return new AbstractMap.SimpleEntry<>(bm.getKeyExtent(tableId), bm.getFiles());
      }
    }

  }

  /**
   * Read Json array of Bulk.Mapping objects and return SortedMap of the bulk load mapping
   *
   * @throws IOException
   * @throws UnsupportedEncodingException
   */
  public static LoadMappingIterator readLoadMapping(String bulkDir, Table.ID tableId, Input fs)
      throws IOException {
    final Path lmFile = new Path(bulkDir, Constants.BULK_LOAD_MAPPING);
    JsonReader reader = new JsonReader(
        new BufferedReader(new InputStreamReader(fs.open(lmFile), UTF_8)));
    reader.beginArray();
    return new LoadMappingIterator(tableId, reader);
  }

  public static void writeRenameMap(Map<String,String> oldToNewNameMap, String bulkDir,
      Table.ID tableId, Output fs) throws AcceptableThriftTableOperationException {
    final Path renamingFile = new Path(bulkDir, Constants.BULK_RENAME_FILE);
    try (OutputStream fsOut = fs.create(renamingFile);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fsOut))) {
      Gson gson = new GsonBuilder().create();
      gson.toJson(oldToNewNameMap, writer);
    } catch (IOException e) {
      // TODO don't throw this
      throw new AcceptableThriftTableOperationException(tableId.canonicalID(), "",
          TableOperation.BULK_IMPORT, TableOperationExceptionType.BULK_BAD_LOAD_MAPPING,
          e.getMessage());
    }
  }

  public static Map<String,String> readRenameMap(String bulkDir, Table.ID tableId, Input fs)
      throws IOException {
    final Path renamingFile = new Path(bulkDir, Constants.BULK_RENAME_FILE);
    Map<String,String> oldToNewNameMap;
    Gson gson = createGson();
    try (InputStream fis = fs.open(renamingFile);
        BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {
      oldToNewNameMap = gson.fromJson(reader, new TypeToken<Map<String,String>>() {}.getType());
    }
    return oldToNewNameMap;
  }

  /**
   * Read in both maps and change all the file names in the mapping to the new names. This is needed
   * because the load mapping file was written with the original file names before they were moved
   * by BulkImportMove
   */
  public static LoadMappingIterator getUpdatedLoadMapping(String bulkDir, Table.ID tableId,
      Input fs) throws IOException {
    Map<String,String> renames = readRenameMap(bulkDir, tableId, fs);
    LoadMappingIterator lmi = readLoadMapping(bulkDir, tableId, fs);
    lmi.setRenameMap(renames);
    return lmi;
  }

  /**
   * Get a list of files to ingest from the bulk dir
   */
  public static List<String> getAllFiles(String bulkDir, Table.ID tableId, Input fs)
      throws IOException {
    Map<String,String> renames = readRenameMap(bulkDir, tableId, fs);
    List<String> allFiles = new ArrayList<>();
    renames.forEach((oldName, newName) -> {
      if (!newName.endsWith(".json"))
        allFiles.add(newName);
    });
    return allFiles;
  }
}
