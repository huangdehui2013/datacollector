/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.credential;

import com.google.common.collect.ImmutableSet;
import com.streamsets.datacollector.config.CredentialStoreDefinition;
import com.streamsets.datacollector.config.StageLibraryDefinition;
import com.streamsets.datacollector.definition.CredentialStoreDefinitionExtractor;
import com.streamsets.datacollector.stagelibrary.StageLibraryTask;
import com.streamsets.datacollector.util.Configuration;
import com.streamsets.lib.security.http.HeadlessSSOPrincipal;
import com.streamsets.lib.security.http.SSOPrincipal;
import com.streamsets.lib.security.http.SSOPrincipalJson;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.credential.CredentialStore;
import com.streamsets.pipeline.api.credential.CredentialStoreDef;
import com.streamsets.pipeline.api.impl.Utils;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

import javax.security.auth.Subject;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TestCredentialStoresTaskImpl {

  @CredentialStoreDef(label = "label", description = "desc") public static class MyCredentialStore
      implements CredentialStore {
    @Override
    public List<ConfigIssue> init(Context context) {
      List<ConfigIssue> issues = new ArrayList<>();
      if (!context.getId().equals("id")) {
        issues.add(context.createConfigIssue(Errors.CREDENTIAL_STORE_000, "no ID"));
      }
      if (!context.getConfig("foo").equals("bar")) {
        issues.add(context.createConfigIssue(Errors.CREDENTIAL_STORE_000, "no config"));
      }
      return issues;
    }

    @Override
    public String get(String group, String name, String credentialStoreOptions) throws StageException {
      return group + ":" + name + ":" + credentialStoreOptions;
    }

    @Override
    public void destroy() {

    }
  }

  @Test
  public void testLifecycle() {
    Configuration conf = new Configuration();
    StageLibraryTask libraryTask = Mockito.mock(StageLibraryTask.class);
    CredentialStoresTaskImpl storeTask = new CredentialStoresTaskImpl(conf, libraryTask);
    Map<String, CredentialStore> stores = storeTask.getStores();
    Assert.assertNotNull(stores);
    Assert.assertTrue(stores.isEmpty());

    storeTask = Mockito.spy(storeTask);

    Mockito.doReturn(Collections.emptyList()).when(storeTask).loadAndInitStores();

    // init
    storeTask.initTask();
    Mockito.verify(storeTask, Mockito.times(1)).loadAndInitStores();
    Assert.assertEquals(stores, Utils.getCredentialStores());

    // stop
    CredentialStore store = Mockito.mock(CredentialStore.class);
    stores.put("id", store);
    storeTask.stopTask();
    Mockito.verify(store, Mockito.times(1)).destroy();
  }

  @Test
  public void TestLoadAndInitStoreGetDestroy() throws Exception {
    StageLibraryDefinition libraryDef = Mockito.mock(StageLibraryDefinition.class);
    Mockito.when(libraryDef.getName()).thenReturn("lib");
    CredentialStoreDefinition storeDef =
        CredentialStoreDefinitionExtractor.get().extract(libraryDef, MyCredentialStore.class);


    Configuration conf = new Configuration();
    conf.set("credentialStores", "id");
    conf.set("credentialStore.id.def", libraryDef.getName() + "::" + storeDef.getName());
    conf.set("credentialStore.id.config.foo", "bar");
    StageLibraryTask libraryTask = Mockito.mock(StageLibraryTask.class);
    Mockito.when(libraryTask.getCredentialStoreDefinitions()).thenReturn(ImmutableList.of(storeDef));
    CredentialStoresTaskImpl storeTask = new CredentialStoresTaskImpl(conf, libraryTask);

    storeTask.initTask();

    CredentialStore store = storeTask.getStores().get("id");
    Assert.assertTrue(store instanceof ClassloaderInContextCredentialStore);

    SSOPrincipal principal = new SSOPrincipalJson();
    principal.getGroups().add("g");
    Subject subject = new Subject();
    subject.getPrincipals().add(principal);

    // enforcing OK
    Subject.doAs(subject, (PrivilegedExceptionAction<Object>) () -> store.get("g", "n", "o"));

    // enforcing Fail
    try {
      Subject.doAs(subject, (PrivilegedExceptionAction<Object>) () -> store.get("h", "n", "o"));
      Assert.fail();
    } catch (Exception ex) {
      Assert.assertTrue(ex.getCause() instanceof StageException);
    }

    // headless enforcing OK
    principal = new HeadlessSSOPrincipal("uid", ImmutableSet.of("g"));
    subject = new Subject();
    subject.getPrincipals().add(principal);
    Subject.doAs(subject, (PrivilegedExceptionAction<Object>) () -> store.get("g", "n", "o"));

    // headless enforcing Fail
    try {
      principal = new HeadlessSSOPrincipal("uid", ImmutableSet.of("g"));
      subject = new Subject();
      subject.getPrincipals().add(principal);
      Subject.doAs(subject, (PrivilegedExceptionAction<Object>) () -> store.get("h", "n", "o"));
      Assert.fail();
    } catch (Exception ex) {
      Assert.assertTrue(ex.getCause() instanceof StageException);
    }

    // headless not enforcing
    principal = HeadlessSSOPrincipal.createRecoveryPrincipal("uid");
    subject = new Subject();
    subject.getPrincipals().add(principal);
    Subject.doAs(subject, (PrivilegedExceptionAction<Object>) () -> store.get("g", "n", "o"));

    storeTask.stopTask();
  }

  @Test
  public void testCreateContext() {
    Configuration conf = new Configuration();
    conf.set("credentialStore.id.config.foo", "bar");
    StageLibraryTask libraryTask = Mockito.mock(StageLibraryTask.class);
    CredentialStoresTaskImpl storeTask = new CredentialStoresTaskImpl(conf, libraryTask);

    CredentialStore.Context context = storeTask.createContext("id", conf);
    Assert.assertEquals("id", context.getId());
    Assert.assertEquals("bar", context.getConfig("foo"));

    CredentialStore.ConfigIssue issue = context.createConfigIssue(Errors.CREDENTIAL_STORE_000, "MESSAGE");
    Assert.assertTrue(issue.toString().contains("CREDENTIAL_STORE_000"));
    Assert.assertTrue(issue.toString().contains("MESSAGE"));
  }

}
