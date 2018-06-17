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
package org.apache.solr.handler;

import static java.util.Arrays.asList;
import static org.apache.solr.common.util.Utils.getObjectByPath;

import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.util.TimeUnits;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient.SimpleResponse;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkConfigManager;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.SolrConfig;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.junit.Test;
import org.noggit.JSONParser;
import org.noggit.ObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.randomizedtesting.annotations.TimeoutSuite;

@Slow
@TimeoutSuite(millis = 60 * TimeUnits.SECOND)
public class TestConfigReload extends AbstractFullDistribZkTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Test
  public void test() throws Exception {
    setupRestTestHarnesses();
    reloadTest();
  }

  private void reloadTest() throws Exception {
    SolrZkClient client = cloudClient.getZkStateReader().getZkClient();
    log.info("live_nodes_count :  " + cloudClient.getZkStateReader().getClusterState().getLiveNodes());
    String confPath = ZkConfigManager.CONFIGS_ZKNODE+"/conf1/";
//    checkConfReload(client, confPath + ConfigOverlay.RESOURCE_NAME, "overlay");
    checkConfReload(client, confPath + SolrConfig.DEFAULT_CONF_FILE,"config", "/config");

  }

  private void checkConfReload(SolrZkClient client, String resPath, String name, String uri) throws Exception {
    Stat stat =  new Stat();
    byte[] data = null;
    try {
      data = client.getData(resPath, null, stat, true);
    } catch (KeeperException.NoNodeException e) {
      data = "{}".getBytes(StandardCharsets.UTF_8);
      log.info("creating_node {}",resPath);
      client.create(resPath,data, CreateMode.PERSISTENT,true);
    }
    long startTime = System.nanoTime();
    Stat newStat = client.setData(resPath, data, true);
    client.setData("/configs/conf1", new byte[]{1}, true);
    assertTrue(newStat.getVersion() > stat.getVersion());
    log.info("new_version "+ newStat.getVersion());
    Integer newVersion = newStat.getVersion();
    long maxTimeoutSeconds = 60;
    DocCollection coll = cloudClient.getZkStateReader().getClusterState().getCollection("collection1");
    List<String> urls = new ArrayList<>();
    for (Slice slice : coll.getSlices()) {
      for (Replica replica : slice.getReplicas())
        urls.add(""+replica.get(ZkStateReader.BASE_URL_PROP) + "/"+replica.get(ZkStateReader.CORE_NAME_PROP));
    }
    HashSet<String> succeeded = new HashSet<>();

    while ( TimeUnit.SECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS) < maxTimeoutSeconds){
      Thread.sleep(50);
      for (String url : urls) {
        Map respMap = getAsMap(url+uri);
        if(String.valueOf(newVersion).equals(String.valueOf( getObjectByPath(respMap, true, asList(name, "znodeVersion"))))){
          succeeded.add(url);
        }
      }
      if(succeeded.size() == urls.size()) break;
      succeeded.clear();
    }
    assertEquals(StrUtils.formatString("tried these servers {0} succeeded only in {1} ", urls, succeeded) , urls.size(), succeeded.size());
  }

  private Map getAsMap(String uri) throws Exception {
    try (Http2SolrClient solrClient = new Http2SolrClient.Builder(uri).withHttpClient(getHttpClient()).build()) {

      SimpleResponse rsp = solrClient.httpGet(uri);

      return (Map) ObjectBuilder.getVal(new JSONParser(new StringReader(rsp.asString)));
    }
  }

}
