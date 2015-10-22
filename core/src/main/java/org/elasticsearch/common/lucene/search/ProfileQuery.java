/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.lucene.search;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.elasticsearch.search.profile.InternalProfileBreakdown;
import org.elasticsearch.search.profile.InternalProfiler;
import org.elasticsearch.search.profile.ProfileBreakdown;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;


/**
 * A wrapper abstract class, whose only purpose is to organize
 * useful components like ProfileWeight and ProfileScorer
 */
public abstract class ProfileQuery {

    /**
     * ProfileWeight wraps the query's weight and performs timing on:
     *  - scorer()
     *  - bulkScorer()
     *  - normalize()
     *
     * The rest of the methods are delegated to the wrapped weight directly
     * without timing.
     */
    public static class ProfileWeight extends Weight {

        private final Weight subQueryWeight;
        private final ProfileBreakdown profile;

        public ProfileWeight(Query query, Weight subQueryWeight, ProfileBreakdown profile) throws IOException {
            super(query);
            this.subQueryWeight = subQueryWeight;
            this.profile = profile;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            profile.startTime(InternalProfileBreakdown.TimingType.BUILD_SCORER);
            Scorer subQueryScorer = subQueryWeight.scorer(context);
            profile.stopAndRecordTime(InternalProfileBreakdown.TimingType.BUILD_SCORER);
            if (subQueryScorer == null) {
                return null;
            }

            return new ProfileScorer(this, subQueryScorer, profile);
        }

        @Override
        public BulkScorer bulkScorer(LeafReaderContext context) throws IOException {
            // We use the default bulk scorer instead of the specialized one. The reason
            // is that Lucene's BulkScorers do everything at once: finding matches,
            // scoring them and calling the collector, so they make it impossible to
            // see where time is spent, which is the purpose of query profiling.
            // The default bulk scorer will pull a scorer and iterate over matches,
            // this might be a significantly different execution path for some queries
            // like disjunctions, but in general this is what is done anyway
            return super.bulkScorer(context);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            return subQueryWeight.explain(context, doc);
        }

        @Override
        public float getValueForNormalization() throws IOException {
            return subQueryWeight.getValueForNormalization();
        }

        @Override
        public void normalize(float norm, float topLevelBoost) {
            subQueryWeight.normalize(norm, topLevelBoost);
        }

        @Override
        public void extractTerms(Set<Term> set) {
            subQueryWeight.extractTerms(set);
        }
    }


    /**
     * ProfileScorer wraps the query's scorer and performs timing on:
     *  - score()
     *
     * The rest of the methods are delegated to the wrapped scorer directly
     * without any timing.  Notably, docID(), advance() and nextDoc() are
     * not timed since those are called recursively and will inflate timings
     */
    public static class ProfileScorer extends Scorer {

        private final Scorer scorer;
        private ProfileWeight profileWeight;
        private final ProfileBreakdown profile;

        private ProfileScorer(ProfileWeight w, Scorer scorer, ProfileBreakdown profile) throws IOException {
            super(w);
            this.scorer = scorer;
            this.profileWeight = w;
            this.profile = profile;
        }

        @Override
        public int docID() {
            return scorer.docID();
        }

        @Override
        public int advance(int target) throws IOException {
            profile.startTime(InternalProfileBreakdown.TimingType.ADVANCE);
            try {
                return scorer.advance(target);
            } finally {
                profile.stopAndRecordTime(InternalProfileBreakdown.TimingType.ADVANCE);
            }
        }

        @Override
        public int nextDoc() throws IOException {
            profile.startTime(InternalProfileBreakdown.TimingType.NEXT_DOC);
            try {
                return scorer.nextDoc();
            } finally {
                profile.stopAndRecordTime(InternalProfileBreakdown.TimingType.NEXT_DOC);
            }
        }

        @Override
        public float score() throws IOException {
            profile.startTime(InternalProfileBreakdown.TimingType.SCORE);
            try {
                return scorer.score();
            } finally {
                profile.stopAndRecordTime(InternalProfileBreakdown.TimingType.SCORE);
            }
        }

        @Override
        public int freq() throws IOException {
            return scorer.freq();
        }

        @Override
        public long cost() {
            return scorer.cost();
        }

        @Override
        public Weight getWeight() {
            return profileWeight;
        }

        @Override
        public Collection<ChildScorer> getChildren() {
            return scorer.getChildren();
        }

        @Override
        public TwoPhaseIterator asTwoPhaseIterator() {
            final TwoPhaseIterator in = scorer.asTwoPhaseIterator();
            if (in == null) {
                return null;
            }
            final DocIdSetIterator inApproximation = in.approximation();
            final DocIdSetIterator approximation = new DocIdSetIterator() {

                @Override
                public int advance(int target) throws IOException {
                    profile.startTime(InternalProfileBreakdown.TimingType.ADVANCE);
                    try {
                        return inApproximation.advance(target);
                    } finally {
                        profile.stopAndRecordTime(InternalProfileBreakdown.TimingType.ADVANCE);
                    }
                }

                @Override
                public int nextDoc() throws IOException {
                    profile.startTime(InternalProfileBreakdown.TimingType.NEXT_DOC);
                    try {
                        return inApproximation.nextDoc();
                    } finally {
                        profile.stopAndRecordTime(InternalProfileBreakdown.TimingType.NEXT_DOC);
                    }
                }

                @Override
                public int docID() {
                    return inApproximation.docID();
                }

                @Override
                public long cost() {
                    return inApproximation.cost();
                }
            };
            return new TwoPhaseIterator(approximation) {
                @Override
                public boolean matches() throws IOException {
                    profile.startTime(InternalProfileBreakdown.TimingType.MATCH);
                    try {
                        return in.matches();
                    } finally {
                        profile.stopAndRecordTime(InternalProfileBreakdown.TimingType.MATCH);
                    }
                }
            };
        }
    }

}