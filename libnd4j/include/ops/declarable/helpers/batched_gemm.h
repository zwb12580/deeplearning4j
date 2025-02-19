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
//  @author raver119@gmail.com
//
#include <array/NDArray.h>

#include <vector>

namespace sd {
namespace ops {
namespace helpers {

SD_LIB_HIDDEN  void bgemm( sd:: NDArray *a,  sd::NDArray *b,  sd::NDArray *c,
                          NDArray* alphas,  NDArray* betas, int transA, int transB, int M, int N, int K,
                          int lda,  int ldb,  int ldc, sd::NDArray *all = nullptr);

SD_LIB_HIDDEN void bgemm( std::vector<NDArray*>& vA,  std::vector<NDArray*>& vB, std::vector<NDArray*>& vC,
                          NDArray* alphas,  NDArray* betas, int transA, int transB, int M, int N, int K,
                          int lda,  int ldb,  int ldc);

}
}  // namespace ops
}  // namespace sd
