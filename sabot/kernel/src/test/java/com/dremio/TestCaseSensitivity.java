/*
 * Copyright (C) 2017-2019 Dremio Corporation
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

package com.dremio;

import org.junit.Test;

public class TestCaseSensitivity extends BaseTestQuery {

  @Test //DRILL-4707
  public void testCaseSenWhenQueryTwoDiffCols() throws Exception {
    // 1st column integer, 2nd column varchar
    testBuilder()
        .sqlQuery("select n_nationkey as XYZ, n_name as xyz FROM cp.\"tpch/nation.parquet\" order by n_nationkey limit 1")
        .ordered()
        .baselineColumns("XYZ", "xyz0")
        .baselineValues(0, "ALGERIA")
        .build()
        .run();

    // both columns integer type
    testBuilder()
        .sqlQuery("select n_nationkey as XYZ, n_regionkey as xyz FROM cp.\"tpch/nation.parquet\" order by n_nationkey limit 1")
        .ordered()
        .baselineColumns("XYZ", "xyz0")
        .baselineValues(0, 0)
        .build()
        .run();

    // join two tables. 1st column integer, 2nd column varchar
    testBuilder()
        .sqlQuery("select n.n_nationkey as XYZ, r.r_name as xyz from cp.\"tpch/nation.parquet\" n, cp.\"tpch/region.parquet\" r where n.n_regionkey = r.r_regionkey order by n.n_nationkey limit 1")
        .ordered()
        .baselineColumns("XYZ", "xyz0")
        .baselineValues(0, "AFRICA")
        .build()
        .run();
  }

}
