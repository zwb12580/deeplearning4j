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
// @author raver119@gmail.com
//

#ifndef LIBND4J_GNODE_H
#define LIBND4J_GNODE_H
#include <array/NDArray.h>
#include <graph/scheme/node_generated.h>
#include <ops/declarable/DeclarableOp.h>

#include <atomic>
#include <string>

#include "Context.h"

namespace sd {
namespace graph {

class Graph;

class SD_LIB_EXPORT Node {
 protected:
  // TODO: this field must be removed
  sd::DataType _dataType;

  OpType _opType;
  ContextPrototype *_protoContext = nullptr;
  sd::LongType _opNum;
  int _id;
  std::vector<std::pair<int, int>> _input;
  std::vector<std::pair<int, int>> _output;
  std::vector<int> _dimensions;

  std::vector<int> _referencedBy;

  int *_dim = nullptr;
  std::string _name;

  // this variable points to onion layer within graph
  int _layer = -1;

  // many ops require extra parameters to run
  double *_extraParams = nullptr;

  // optional scalar. used in scalar ops and in summary stats
  // TODO: this field must be removed
  NDArray _scalar;

  bool _hasExternalOutputs;
  bool _hasExternalInputs;
  bool _hasInternalOutputs;
  bool _hasInternalInputs;

  // this field is used to check, if op should be used in-place (so it can/will modify its inputs)
  bool _isInplace = false;

  // this field is used to delete attached customOp
  bool _isDeductable = false;

  OpClass _opClass;

  // these fields are used to store embedded CustomOps and Graph in case of Graph-in-Graph scenario
  sd::graph::Graph *_graph = nullptr;
  sd::ops::DeclarableOp *_customOp = nullptr;

  // each node can be active or inactive, if used with divergents, like IF statements
  bool _active = true;

  // these fields contain information about Scope these ops are related to
  int _scope_id = 0;
  std::string _scope_name;

  // TODO: these 3 fields should be removed
  int _rewindNode = -1;
  std::pair<int, int> _rewindLayer = {-1, -1};
  sd::LongType _frameId = -1;

 public:
  explicit Node(sd::ops::DeclarableOp *customOp, int id = 0, std::initializer_list<int> input = {},
                std::initializer_list<int> output = {}, std::initializer_list<int> dimensions = {}, float scalar = 0.0f,
                std::initializer_list<double> tArgs = {}, std::initializer_list<int> iArgs = {});
  explicit Node(OpType opType = OpType_TRANSFORM_SAME, int opNum = 0, int id = 0, std::initializer_list<int> input = {},
                std::initializer_list<int> output = {}, std::initializer_list<int> dimensions = {}, float scalar = 0.0f,
                std::initializer_list<double> tArgs = {}, std::initializer_list<int> iArgs = {});
  explicit Node(const sd::graph::FlatNode *node);
  ~Node();

  bool equals(Node *other);

  sd::DataType dataType();
  ContextPrototype *protoContext();
  OpType opType();
  sd::LongType opNum();
  int id();
  std::vector<std::pair<int, int>> *input();
  std::vector<std::pair<int, int>> *output();

  sd::LongType getFrameId();
  void setFrameId(sd::LongType frameId);

  int getRewindNode();
  void setRewindNode(int nodeId);

  std::pair<int, int> &getRewindLayer();
  void setRewindLayer(int layerId, int stepId = 0);

  void setId(int id);

  double *extraParams();

  bool isMultiInput();
  bool isMultiOutput();

  int getLayer();
  void setLayer(int layer);

  bool isDivergencePoint();
  void setActive(bool reallyActive);
  bool isActive();

  bool hasExternalOutputs();
  bool hasExternalInputs();
  bool hasInternalOutputs();
  bool hasInternalInputs();

  double scalar();

  std::vector<int> *getDimensions();
  int *getDimensionsPtr();

  void pickOutputOnce(int outputId);
  void pickOutput(int outputId);
  void pickOutput(int nodeId, int outputId);
  void pickExternalOutput(int outputId);
  void pickInput(int inputId);
  void pickInput(int nodeId, int outputId);
  void pickInput(std::pair<int, int> &id);

  bool isDeductable();
  void setDeductable(bool reallyDeductable);

  void setName(std::string *name);
  void setName(const std::string &name);
  std::string *getName();
  std::string *name();

  int totalReferences();
  void addReference(int nodeId);

  void setContextPrototype(ContextPrototype *block);
  ContextPrototype *getContextPrototype();
  bool hasBlockAttached();

  void setCustomOp(sd::ops::DeclarableOp *customOp = nullptr);
  sd::ops::DeclarableOp *getCustomOp();
  bool hasCustomOp();

  void setGraph(sd::graph::Graph *graph = nullptr);
  sd::graph::Graph *getGraph();
  bool hasGraphEmbedded();

  bool isInplace();
  void markInplace(bool reallyInplace);

  OpClass getOpClass();

  // these methods are used for internal profiling
  void setOuterTime(sd::LongType time);
  void setInnerTime(sd::LongType time);

  // methods related to scopes
  bool isScoped();
  void setScopeInfo(int id, const char *name = nullptr);
  int scopeId();
  std::string *scopeName();

  void setOpType(OpType opType);

  // clone Node
  Node *clone();

  template <typename T>
  Node *asT();

  SD_INLINE void pullValues(Node *other) {
    if (this->_protoContext != nullptr) delete _protoContext;

    this->_dataType = other->dataType();
    this->_protoContext = other->protoContext()->clone();
    this->_scalar = other->scalar();
    this->_hasExternalInputs = other->hasExternalInputs();
    this->_hasExternalOutputs = other->hasExternalOutputs();
    this->_hasInternalInputs = other->hasInternalInputs();
    this->_hasInternalOutputs = other->hasInternalOutputs();

    this->markInplace(other->isInplace());
    this->setActive(other->isActive());
    this->setScopeInfo(other->scopeId(), other->scopeName()->c_str());
    this->setLayer(other->getLayer());
    this->setDeductable(other->isDeductable());

    if (this->_customOp != nullptr && _isDeductable) delete this->_customOp;

    for (auto v : *other->input()) this->_input.emplace_back(v);

    for (auto v : *other->output()) this->_output.emplace_back(v);

    for (auto v : *other->getDimensions()) this->_dimensions.emplace_back(v);
  }

  static sd::ops::DeclarableOp *buildOpByType(OpType opType, int numInputs, int numIArgs, int numTArgs, int opNum,
                                              NDArray *scalar);
  static void deleteOpByType(OpType opType, void *op);
};
}  // namespace graph
}  // namespace sd

#endif  // LIBND4J_GNODE_H
