/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.cluster.gracefulstop;

import io.crate.action.sql.TransportSQLAction;
import io.crate.action.sql.TransportSQLBulkAction;
import io.crate.operation.collect.StatsTables;
import org.elasticsearch.action.admin.cluster.health.TransportClusterHealthAction;
import org.elasticsearch.action.admin.cluster.settings.TransportClusterUpdateSettingsAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.test.cluster.NoopClusterService;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;

import javax.annotation.Nullable;
import java.util.UUID;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

public class DecommissioningServiceTest {

    private StatsTables statsTables;
    private TestableDecommissioningService decommissioningService;
    private ThreadPool threadPool;

    @Before
    public void setUp() throws Exception {
        statsTables = new StatsTables(Settings.EMPTY, mock(NodeSettingsService.class));
        threadPool = mock(ThreadPool.class, Answers.RETURNS_MOCKS.get());
        decommissioningService = new TestableDecommissioningService(
            Settings.EMPTY,
            new NoopClusterService(),
            statsTables,
            threadPool,
            mock(NodeSettingsService.class),
            mock(TransportSQLAction.class),
            mock(TransportSQLBulkAction.class),
            mock(TransportClusterHealthAction.class),
            mock(TransportClusterUpdateSettingsAction.class)
        );
    }

    @Test
    public void testExitIfNoActiveRequests() throws Exception {
        decommissioningService.exitIfNoActiveRequests(0);
        assertThat(decommissioningService.exited, is(true));
        assertThat(decommissioningService.forceStopOrAbortCalled, is(false));
    }

    @Test
    public void testNoExitIfRequestAreActive() throws Exception {
        statsTables.jobFinished(UUID.randomUUID(), null);
        decommissioningService.exitIfNoActiveRequests(System.nanoTime());
        assertThat(decommissioningService.exited, is(false));
        assertThat(decommissioningService.forceStopOrAbortCalled, is(false));
        verify(threadPool, times(1)).scheduler();
    }

    @Test
    public void testAbortOrForceStopIsCalledOnTimeout() throws Exception {
        statsTables.jobFinished(UUID.randomUUID(), null);
        decommissioningService.exitIfNoActiveRequests(System.nanoTime() - TimeValue.timeValueHours(3).nanos());
        assertThat(decommissioningService.forceStopOrAbortCalled, is(true));
    }

    private static class TestableDecommissioningService extends DecommissioningService {

        private boolean exited = false;
        private boolean forceStopOrAbortCalled = false;

        public TestableDecommissioningService(Settings settings,
                                              ClusterService clusterService,
                                              StatsTables statsTables,
                                              ThreadPool threadPool,
                                              NodeSettingsService nodeSettingsService,
                                              TransportSQLAction sqlAction,
                                              TransportSQLBulkAction sqlBulkAction,
                                              TransportClusterHealthAction healthAction,
                                              TransportClusterUpdateSettingsAction updateSettingsAction) {
            super(settings, clusterService, statsTables, threadPool, nodeSettingsService,
                sqlAction, sqlBulkAction, healthAction, updateSettingsAction);
        }

        @Override
        void exit() {
            exited = true;
        }

        @Override
        void forceStopOrAbort(@Nullable Throwable e) {
            forceStopOrAbortCalled = true;
        }
    }
}
