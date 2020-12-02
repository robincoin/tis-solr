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
package com.qlangtech.tis.offline.pojo;

import com.qlangtech.tis.git.GitUtils.JoinRule;

/**
 *  Git仓库保存的workflow信息
 *
 * @author 百岁（baisui@qlangtech.com）
 * @date 2020/04/13
 */
public class WorkflowPojo {

    private String name;
    private JoinRule task;

    public WorkflowPojo() {
    }

    public WorkflowPojo(String name, JoinRule task) {
        this.name = name;
        this.task = task;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JoinRule getTask() {
        return task;
    }

    public void setTask(JoinRule task) {
        this.task = task;
    }
}
