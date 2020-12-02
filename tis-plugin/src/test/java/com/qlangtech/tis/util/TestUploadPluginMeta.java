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
package com.qlangtech.tis.util;

import junit.framework.TestCase;

import java.util.List;

/**
 * @author 百岁（baisui@qlangtech.com）
 * @create: 2020-07-20 13:21
 */
public class TestUploadPluginMeta extends TestCase {

    public void testPluginMetaParse() {
        String pluginName = "test_plugin";
        String[] plugins = new String[]{pluginName + ":require"};
        List<UploadPluginMeta> pluginMetas = UploadPluginMeta.parse(plugins);
        assertEquals(1, pluginMetas.size());
        UploadPluginMeta meta = pluginMetas.get(0);
        assertNotNull(meta);
        assertEquals(pluginName, meta.getName());
        assertTrue(meta.isRequired());
        // ===============================================
        plugins = new String[]{pluginName};
        pluginMetas = UploadPluginMeta.parse(plugins);
        assertEquals(1, pluginMetas.size());
        meta = pluginMetas.get(0);
        assertNotNull(meta);
        assertEquals(pluginName, meta.getName());
        assertFalse(meta.isRequired());
        // ==============================================
        plugins = new String[]{pluginName + ":xxxx"};
        try {
            pluginMetas = UploadPluginMeta.parse(plugins);
            fail("shall be faild,but not");
        } catch (Exception e) {
        }

        plugins = new String[]{pluginName + ":datasource_order,require"};

        pluginMetas = UploadPluginMeta.parse(plugins);

        assertEquals(1, pluginMetas.size());
        meta = pluginMetas.get(0);
        assertNotNull(meta);
        assertEquals(pluginName, meta.getName());
        assertTrue(meta.isRequired());
        assertEquals("order", meta.getExtraParam("datasource"));
    }
}
