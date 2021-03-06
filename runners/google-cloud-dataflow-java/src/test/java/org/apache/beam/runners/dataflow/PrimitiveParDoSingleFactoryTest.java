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

package org.apache.beam.runners.dataflow;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.collect.Iterables;
import java.io.Serializable;
import java.util.List;
import org.apache.beam.runners.dataflow.PrimitiveParDoSingleFactory.ParDoSingle;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Sum;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.DisplayDataEvaluator;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link PrimitiveParDoSingleFactory}.
 */
@RunWith(JUnit4.class)
public class PrimitiveParDoSingleFactoryTest implements Serializable {
  // Create a pipeline for testing Side Input propagation. This won't actually run any Pipelines,
  // so disable enforcement.
  @Rule
  public transient TestPipeline pipeline =
      TestPipeline.create().enableAbandonedNodeEnforcement(false);

  private PrimitiveParDoSingleFactory<Integer, Long> factory = new PrimitiveParDoSingleFactory<>();

  /**
   * A test that demonstrates that the replacement transform has the Display Data of the
   * {@link ParDo.Bound} it replaces.
   */
  @Test
  public void getReplacementTransformPopulateDisplayData() {
    ParDo.Bound<Integer, Long> originalTransform = ParDo.of(new ToLongFn());
    DisplayData originalDisplayData = DisplayData.from(originalTransform);

    PTransform<PCollection<? extends Integer>, PCollection<Long>> replacement =
        factory.getReplacementTransform(originalTransform);
    DisplayData replacementDisplayData = DisplayData.from(replacement);

    assertThat(replacementDisplayData, equalTo(originalDisplayData));

    DisplayData primitiveDisplayData =
        Iterables.getOnlyElement(
            DisplayDataEvaluator.create()
                .displayDataForPrimitiveTransforms(replacement, VarIntCoder.of()));
    assertThat(primitiveDisplayData, equalTo(replacementDisplayData));
  }

  @Test
  public void getReplacementTransformGetSideInputs() {
    PCollectionView<Long> sideLong =
        pipeline
            .apply("LongSideInputVals", Create.of(-1L, -2L, -4L))
            .apply("SideLongView", Sum.longsGlobally().asSingletonView());
    PCollectionView<List<String>> sideStrings =
        pipeline
            .apply("StringSideInputVals", Create.of("foo", "bar", "baz"))
            .apply("SideStringsView", View.<String>asList());
    ParDo.Bound<Integer, Long> originalTransform =
        ParDo.of(new ToLongFn()).withSideInputs(sideLong, sideStrings);

    PTransform<PCollection<? extends Integer>, PCollection<Long>> replacementTransform =
        factory.getReplacementTransform(originalTransform);
    ParDoSingle<Integer, Long> parDoSingle = (ParDoSingle<Integer, Long>) replacementTransform;
    assertThat(parDoSingle.getSideInputs(), containsInAnyOrder(sideStrings, sideLong));
  }

  @Test
  public void getReplacementTransformGetFn() {
    DoFn<Integer, Long> originalFn = new ToLongFn();
    ParDo.Bound<Integer, Long> originalTransform = ParDo.of(originalFn);
    PTransform<PCollection<? extends Integer>, PCollection<Long>> replacementTransform =
        factory.getReplacementTransform(originalTransform);
    ParDoSingle<Integer, Long> parDoSingle = (ParDoSingle<Integer, Long>) replacementTransform;

    assertThat(parDoSingle.getFn(), equalTo(originalTransform.getFn()));
    assertThat(parDoSingle.getFn(), equalTo(originalFn));
  }

  private static class ToLongFn extends DoFn<Integer, Long> {
    @ProcessElement
    public void toLong(ProcessContext ctxt) {
      ctxt.output(ctxt.element().longValue());
    }

    public boolean equals(Object other) {
      return other != null && other.getClass().equals(getClass());
    }

    public int hashCode() {
      return getClass().hashCode();
    }
  }
}
