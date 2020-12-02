/**
 * Copyright (c) 2020 QingLang, Inc. <baisui@qlangtech.com>
 * <p>
 * This program is free software: you can use, redistribute, and/or modify
 * it under the terms of the GNU Affero General Public License, version 3
 * or later ("AGPL"), as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.qlangtech.tis.db.parser.domain;

import com.alibaba.fastjson.JSONObject;
import com.qlangtech.tis.plugin.ds.DataSourceFactory;
import com.qlangtech.tis.util.DescribableJSON;

/**
 * @author 百岁（baisui@qlangtech.com）
 * @date 2020/04/13
 */
public class DBConfigSuit {

  private DescribableJSON detailed;

  public JSONObject getDetailed() throws Exception {
    return detailed.getItemJson();
  }

  public void setDetailed(DataSourceFactory detailed) {
    if (detailed == null) {
      throw new IllegalStateException("param detailed can not be null");
    }
    this.detailed = new DescribableJSON(detailed);
  }
  //
//    private DBConfig facade;

//    public DBConfig getDetailed() {
//        return this.detailed;
//    }
//
//    public void setDetailed(DBConfig detailed) {
//        this.detailed = detailed;
//    }
//
//    public DBConfig getFacade() {
//        return this.facade;
//    }
//
//    public void setFacade(DBConfig facade) {
//        this.facade = facade;
//    }
}
