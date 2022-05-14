/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package accord.coordinate;

import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import accord.primitives.*;
import com.google.common.base.Preconditions;

import accord.api.RoutingKey;
import accord.local.Node;
import accord.local.Node.Id;
import accord.messages.CheckStatus.CheckStatusOk;
import accord.messages.CheckStatus.IncludeInfo;

import static accord.utils.Functions.reduceNonNull;

/**
 * A result of null indicates the transaction is globally persistent
 * A result of CheckStatusOk indicates the maximum status found for the transaction, which may be used to assess progress
 */
public class MaybeRecover extends CheckShards
{
    @Nullable final AbstractRoute route;
    final RoutingKey homeKey;
    final ProgressToken prevProgress;
    final BiConsumer<Outcome, Throwable> callback;

    MaybeRecover(Node node, TxnId txnId, RoutingKey homeKey, @Nullable AbstractRoute route, ProgressToken prevProgress, BiConsumer<Outcome, Throwable> callback)
    {
        // we only want to enquire with the home shard, but we prefer maximal route information for running Invalidation against, if necessary
        super(node, txnId, RoutingKeys.of(homeKey), txnId.epoch, IncludeInfo.Route);
        this.homeKey = homeKey;
        this.route = route;
        this.prevProgress = prevProgress;
        this.callback = callback;
    }

    public static void maybeRecover(Node node, TxnId txnId, RoutingKey homeKey, @Nullable AbstractRoute route,
                                    ProgressToken prevProgress, BiConsumer<Outcome, Throwable> callback)
    {
        MaybeRecover maybeRecover = new MaybeRecover(node, txnId, homeKey, route, prevProgress, callback);
        maybeRecover.start();
    }

    @Override
    protected boolean isSufficient(Id from, CheckStatusOk ok)
    {
        return hasMadeProgress(ok);
    }

    public boolean hasMadeProgress(CheckStatusOk ok)
    {
        return ok != null && (ok.isCoordinating // TODO (now): make this coordinatingSince so can be pre-empted by others if stuck
                              || ok.toProgressToken().compareTo(prevProgress) > 0);
    }

    @Override
    protected void onDone(Done done, Throwable fail)
    {
        if (fail != null)
        {
            callback.accept(null, fail);
        }
        else if (merged == null)
        {
            callback.accept(null, new Timeout(txnId, homeKey));
        }
        else
        {
            switch (merged.saveStatus.known)
            {
                default: throw new AssertionError();
                case Nothing:
                    if (!(merged.route instanceof Route))
                    {
                        // order important, as route could be a Route which does not implement RoutingKeys.union
                        RoutingKeys someKeys = reduceNonNull(RoutingKeys::union, this.contactKeys, merged.route, route);
                        Invalidate.invalidateIfNotWitnessed(node, txnId, someKeys, homeKey, callback);
                        break;
                    }
                case ExecutionOrder:
                case Definition:
                case Outcome:
                    Preconditions.checkState(merged.route instanceof Route);
                    if (hasMadeProgress(merged)) callback.accept(merged.toProgressToken(), null);
                    else node.recover(txnId, (Route) merged.route).addCallback(callback);
                    break;

                case Invalidation:
                    RoutingKeys someKeys = reduceNonNull(RoutingKeys::union, this.contactKeys, merged.route, route);
                    Invalidate.invalidate(node, txnId, someKeys, homeKey, callback);
            }
        }
    }
}
