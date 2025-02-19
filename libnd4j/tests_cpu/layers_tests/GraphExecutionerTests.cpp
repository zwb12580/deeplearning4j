/* ******************************************************************************
 *
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

//
// Created by raver119 on 29.11.17.
//

#include <array/NDArray.h>
#include <flatbuffers/flatbuffers.h>
#include <graph/Graph.h>
#include <graph/Node.h>
#include <graph/scheme/graph_generated.h>
#include <graph/scheme/node_generated.h>
#include <ops/declarable/DeclarableOp.h>

#include "testlayers.h"

using namespace sd;
using namespace sd::graph;

class GraphExecutionerTests : public testing::Test {
 public:
};

#ifdef GRAPH_TESTS_OK
TEST_F(GraphExecutionerTests, Test_Implicit_Output_1) {
  auto graph = GraphExecutioner::importFromFlatBuffers("./resources/tensor_slice.fb");
  graph->buildGraph();

  auto outputs = graph->fetchOutputs();

  ASSERT_EQ(1, outputs->size());

  auto var0 = outputs->at(0);

  ASSERT_EQ(7, var0->id());
  ASSERT_EQ(0, var0->index());

  delete outputs;
  delete graph;
}

TEST_F(GraphExecutionerTests, Test_Implicit_Output_2) {
  auto graph = GraphExecutioner::importFromFlatBuffers("./resources/reduce_dim_false.fb");
  graph->buildGraph();

  auto outputs = graph->fetchOutputs();

  ASSERT_EQ(1, outputs->size());

  auto var0 = outputs->at(0);

  ASSERT_EQ(3, var0->id());
  ASSERT_EQ(0, var0->index());

  delete outputs;
  delete graph;
}

TEST_F(GraphExecutionerTests, Test_Implicit_Output_3) {
  auto exp = NDArrayFactory::create<float>('c', {3}, {3, 3, 3});

  auto graph = GraphExecutioner::importFromFlatBuffers("./resources/reduce_dim_false.fb");
  auto status = GraphExecutioner::execute(graph);

  ASSERT_EQ(sd::Status::OK, status);

  auto outputs = graph->fetchOutputs();

  ASSERT_EQ(1, outputs->size());

  auto var0 = outputs->at(0);

  ASSERT_EQ(3, var0->id());
  ASSERT_EQ(0, var0->index());

  auto array = var0->getNDArray();

  ASSERT_TRUE(array != nullptr);

  ASSERT_TRUE(exp.isSameShape(array));
  ASSERT_TRUE(exp.equalsTo(array));

  delete outputs;
  delete graph;
}
#endif
