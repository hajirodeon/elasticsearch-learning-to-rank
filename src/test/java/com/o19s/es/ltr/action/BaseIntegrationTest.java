/*
 * Copyright [2017] Wikimedia Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.o19s.es.ltr.action;

import com.o19s.es.ltr.LtrQueryParserPlugin;
import com.o19s.es.ltr.action.FeatureStoreAction.FeatureStoreRequestBuilder;
import com.o19s.es.ltr.action.FeatureStoreAction.FeatureStoreResponse;
import com.o19s.es.ltr.feature.FeatureValidation;
import com.o19s.es.ltr.feature.store.StorableElement;
import com.o19s.es.ltr.feature.store.index.IndexFeatureStore;
import com.o19s.es.ltr.ranker.parser.LtrRankerParserFactory;
import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexAction;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.o19s.es.ltr.feature.store.ScriptFeature.FEATURE_VECTOR;

public abstract class BaseIntegrationTest extends ESSingleNodeTestCase {
    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Arrays.asList(LtrQueryParserPlugin.class, NativeScriptPlugin.class);
    }

    public void createStore(String name) throws Exception {
        assert IndexFeatureStore.isIndexStore(name);
        CreateIndexResponse resp = client().execute(CreateIndexAction.INSTANCE, IndexFeatureStore.buildIndexRequest(name)).get();
        assertTrue(resp.isAcknowledged());
    }

    @Before
    public void setup() throws Exception {
        createDefaultStore();
    }

    public void deleteDefaultStore() throws Exception {
        deleteStore(IndexFeatureStore.DEFAULT_STORE);
    }

    public void deleteStore(String name) throws Exception {
        DeleteIndexResponse resp = client().admin().indices().prepareDelete(name).get();
        assertTrue(resp.isAcknowledged());
    }

    public void createDefaultStore() throws Exception {
        createStore(IndexFeatureStore.DEFAULT_STORE);
    }

    public FeatureStoreResponse addElement(StorableElement element,
                                           FeatureValidation validation) throws ExecutionException, InterruptedException {
        return addElement(element, validation, IndexFeatureStore.DEFAULT_STORE);
    }

    public FeatureStoreResponse addElement(StorableElement element, String store) throws ExecutionException, InterruptedException {
        return addElement(element, null, store);
    }

    public FeatureStoreResponse addElement(StorableElement element) throws ExecutionException, InterruptedException {
        return addElement(element, null, IndexFeatureStore.DEFAULT_STORE);
    }

    public <E extends StorableElement> E getElement(Class<E> clazz, String type, String name) throws IOException {
        return getElement(clazz, type, name, IndexFeatureStore.DEFAULT_STORE);
    }

    public <E extends StorableElement> E getElement(Class<E> clazz, String type, String name, String store) throws IOException {
        return new IndexFeatureStore(store, client(), parserFactory()).getAndParse(name, clazz, type);
    }

    protected LtrRankerParserFactory parserFactory() {
        return getInstanceFromNode(LtrRankerParserFactory.class);
    }

    public FeatureStoreResponse addElement(StorableElement element,
                                           @Nullable FeatureValidation validation,
                                           String store) throws ExecutionException, InterruptedException {
        FeatureStoreRequestBuilder builder = FeatureStoreAction.INSTANCE.newRequestBuilder(client());
        builder.request().setStorableElement(element);
        builder.request().setAction(FeatureStoreAction.FeatureStoreRequest.Action.CREATE);
        builder.request().setStore(store);
        builder.request().setValidation(validation);
        FeatureStoreResponse response = builder.execute().get();
        assertEquals(1, response.getResponse().getVersion());
        assertEquals(IndexFeatureStore.ES_TYPE, response.getResponse().getType());
        assertEquals(DocWriteResponse.Result.CREATED, response.getResponse().getResult());
        assertEquals(element.id(), response.getResponse().getId());
        assertEquals(store, response.getResponse().getIndex());
        return response;
    }

    public static class NativeScriptPlugin extends Plugin implements ScriptPlugin {
        public static final String FEATURE_EXTRACTOR = "feature_extractor";

        @Override
        public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
            return new ScriptEngine() {
                /**
                 * The language name used in the script APIs to refer to this scripting backend.
                 */
                @Override
                public String getType() {
                    return "native";
                }

                /**
                 * Compiles a script.
                 *
                 * @param scriptName   the name of the script. {@code null} if it is anonymous (inline).
                 *                     For a stored script, its the identifier.
                 * @param scriptSource    actual source of the script
                 * @param context the context this script will be used for
                 * @param params  compile-time parameters (such as flags to the compiler)
                 * @return A compiled script of the FactoryType from {@link ScriptContext}
                 */
                @SuppressWarnings("unchecked")
                @Override
                public <FactoryType> FactoryType compile(String scriptName, String scriptSource,
                                                         ScriptContext<FactoryType> context, Map<String, String> params) {
                    if (context.equals(SearchScript.CONTEXT) == false && (context.equals(SearchScript.AGGS_CONTEXT) == false)) {
                        throw new IllegalArgumentException(getType() + " scripts cannot be used for context [" + context.name
                                + "]");
                    }
                    // we use the script "source" as the script identifier
                    if (FEATURE_EXTRACTOR.equals(scriptSource)) {
                        SearchScript.Factory factory = (p, lookup) ->
                                new SearchScript.LeafFactory() {
                                    final Map<String, Float> featureSupplier;
                                    final String dependentFeature;
                                    public static final String DEPDENDENT_FEATURE = "dependent_feature";

                                    {
                                        if (!p.containsKey(FEATURE_VECTOR)) {
                                            throw new IllegalArgumentException("Missing parameter [" + FEATURE_VECTOR + "]");
                                        }
                                        if (!p.containsKey(DEPDENDENT_FEATURE)) {
                                            throw new IllegalArgumentException("Missing parameter [depdendent_feature ]");
                                        }
                                        featureSupplier = (Map<String, Float>) p.get(FEATURE_VECTOR);
                                        dependentFeature = p.get(DEPDENDENT_FEATURE).toString();
                                    }

                                    @Override
                                    public SearchScript newInstance(LeafReaderContext ctx) throws IOException {
                                        return new SearchScript(p, lookup, ctx) {
                                            @Override
                                            public double runAsDouble() {
                                                return featureSupplier.get(dependentFeature) * 10;
                                            }
                                        };
                                    }

                                    @Override
                                    public boolean needs_score() {
                                        return false;
                                    }
                                };

                        return context.factoryClazz.cast(factory);
                    }
                    throw new IllegalArgumentException("Unknown script name " + scriptSource);
                }
            };
        }
    }
}
