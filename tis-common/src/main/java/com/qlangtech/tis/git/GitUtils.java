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
package com.qlangtech.tis.git;

import com.alibaba.citrus.turbine.impl.DefaultContext;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.qlangtech.tis.db.parser.DBConfigParser;
import com.qlangtech.tis.db.parser.DBTokenizer;
import com.qlangtech.tis.db.parser.domain.DBConfig;
import com.qlangtech.tis.manage.common.*;
import com.qlangtech.tis.manage.common.ConfigFileContext.HTTPMethod;
import com.qlangtech.tis.manage.common.ConfigFileContext.StreamProcess;
import com.qlangtech.tis.manage.common.HttpUtils.PostParam;
import com.qlangtech.tis.offline.DbScope;
import com.qlangtech.tis.offline.pojo.*;
import com.qlangtech.tis.runtime.module.misc.impl.AdapterMessageHandler;
import junit.framework.Assert;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author 百岁（baisui@qlangtech.com）
 * @date 2020/04/13
 */
public class GitUtils implements com.qlangtech.tis.db.IDBConfigAccess {

    private static final ConfigFileContext.Header PRIVATE_TOKEN = new ConfigFileContext.Header("PRIVATE-TOKEN", "XqxWfcskmh9TskxGpEac");

    private static final ConfigFileContext.Header DELETE_METHOD = new ConfigFileContext.Header("method", "DELETE");

    // http://git.2dfire-inc.com.qlangtech.searcher/tis-fullbuild-workflow
    public static final int WORKFLOW_GIT_PROJECT_ID = 1372;

    // http://git.2dfire-inc.com.qlangtech.searcher/tis-fullbuild-datasource-daily
    public static final int DATASOURCE_PROJECT_ID = 1375;

    public static final String TAB_CONFIG_ROOT_DIR = "table_cfg/";

    public static final String GIT_HOST = "http://git.2dfire.net";

    // private static final String WF_FILE_NAME = "join.xml";
    private static final String WF_FILE_NAME = "joinRule";

    // public static final int DATASOURCE_PROJECT_ID_ONLINE = 1374;
    private static final Logger logger = LoggerFactory.getLogger(GitUtils.class);

    public static final String cryptKey = "32&*^%%$$`!h";

    private static final GitPostStreamProcess<String> gitPostStreamProcess = new GitPostStreamProcess<String>() {

        @Override
        public ContentType getContentType() {
            return ContentType.JSON;
        }

        @Override
        public String p(int status, InputStream stream, Map<String, List<String>> headerFields) {
            try {
                return IOUtils.toString(stream, "utf8");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void error(int status, InputStream errstream, IOException e) throws Exception {
            String error = IOUtils.toString(errstream, "utf8");
            JSONTokener tokener = new JSONTokener(error);
            JSONObject result = new JSONObject(tokener);
            throw new Exception(result.getString("message"), e);
        }
    };

    private final String DB_SUB_DIR = "db";

    public final File dbRootDir = new File(Config.getMetaCfgDir(), DB_SUB_DIR);

    private final boolean fetchFromCenterRepository;

    /**
     * 是否要从中央仓库中拉取配置文件
     *
     * @param fetchFromCenterRepository
     */
    private GitUtils(boolean fetchFromCenterRepository) {
        this.fetchFromCenterRepository = fetchFromCenterRepository;
    }

    private static GitUtils SINGLEN;

    public static GitUtils $() {
        if (SINGLEN == null) {
            synchronized (GitUtils.class) {
                if (SINGLEN == null) {
                    boolean fetchFromCenterRepository = !CenterResource.notFetchFromCenterRepository();
                    logger.info("fetchFromCenterRepository:{}", fetchFromCenterRepository);
                    SINGLEN = new GitUtils(fetchFromCenterRepository);
                }
            }
        }
        return SINGLEN;
    }

    public static void main(String[] args) throws Exception {
        System.out.println(Secret.encrypt("order@552208", cryptKey));
        // createFile();
        // getChildren();
        // getProjects();
    }

    private static void createFile() {
        // String urlString = GIT_HOST + "/api/v4/projects/1281/repository/files";
        //
        // List<PostParam> params = new ArrayList<>();
        // params.add(new PostParam("file_path", "server/hello2.txt"));
        // params.add(new PostParam("branch_name", "master"));
        // params.add(new PostParam("encoding", "base64"));
        // params.add(new PostParam("content",
        // Base64.getEncoder().encodeToString("我爱北京天安门".getBytes(Charset.forName("utf8")))));
        // params.add(new PostParam("commit_message", "new added"));
        //
        // HttpUtils.post(urlString, params, new GitPostStreamProcess<String>() {
        // @Override
        // public String p(int status, InputStream stream, String md5) {
        // try {
        // return IOUtils.toString(stream, "utf8");
        // } catch (IOException e) {
        // throw new RuntimeException(e);
        // }
        // }
        // });
    }

    /**
     * 创建
     *
     * @param db
     * @param commitLog
     */
    @Override
    public void createDatabase(TISDb db, String commitLog) {
        processDBConfig(db, commitLog, true, /* is new */
                db.isFacade());
    }

    /**
     * 更新DB的配置
     *
     * @param db
     * @param commitLog
     */
    @Override
    public void updateDatabase(TISDb db, String commitLog) {
        this.processDBConfig(db, commitLog, false, /* is new */
                db.isFacade());
    }

    public static final String DB_CONFIG_META_NAME = "db_config";

    @Override
    public void processDBConfig(TISDb db, String commitLog, boolean isNew, boolean facade) {
        String path = getDBConfigPath(db.getDbName(), (db.isFacade() ? DbScope.FACADE : DbScope.DETAILED));
        this.processDBConfig(db, path, commitLog, isNew, facade);
    }

    /**
     * @param db
     * @param commitLog
     * @param isNew
     * @param facade    是否是cobar类型
     */
    @Override
    public void processDBConfig(TISDb db, String path, String commitLog, boolean isNew, boolean facade) {
        if (StringUtils.isEmpty(db.getDbName())) {
            throw new IllegalArgumentException("param dbName can not be null");
        }
        this.processFile(path, GitBranchInfo.$(GitBranch.DEVELOP), db.createDBConfigDesc(), commitLog, DATASOURCE_PROJECT_ID, isNew ? HTTPMethod.POST : HTTPMethod.PUT);
    }

    @Override
    public String getDBConfigPath(String dbname, DbScope dbscope) {
        return getDBConfigParentPath(dbname) + "/" + DB_CONFIG_META_NAME + dbscope.getDBType();
    }

    private String getDBConfigParentPath(String dbname) {
        return "db/" + dbname;
    }

    /**
     * 将表对象信息序列化存储
     *
     * @param table
     * @param commitLog
     */
//    @Override
//    public void createTableDaily(TISTable table, String commitLog) {
//        if (table == null || StringUtils.isEmpty(table.getDbName()) || StringUtils.isEmpty(table.getTableLogicName())) {
//            throw new IllegalArgumentException("param table can not be null");
//        }
//        if (StringUtils.isEmpty(table.getSelectSql())) {
//            throw new IllegalArgumentException("param selectSql can not be null");
//        }
//        if (StringUtils.isEmpty(commitLog)) {
//            throw new IllegalArgumentException("param commitLog can not be null");
//        }
//        String path = TAB_CONFIG_ROOT_DIR + table.getDbName() + "/" + table.getTableLogicName();
//        final String sql = table.getSelectSql();
//        // 不需要序列化
//        table.setSelectSql(null);
//        boolean newMode = (table.getTabId() == null);
//        this.createFile(path + "/profile", GitBranchInfo.$(GitBranch.DEVELOP), JSON.toJSONString(table, true), commitLog, DATASOURCE_PROJECT_ID, newMode);
//        this.createFile(path + "/sql", GitBranchInfo.$(GitBranch.DEVELOP), sql, commitLog, DATASOURCE_PROJECT_ID, newMode);
//    }

    public static int ExecuteGetTableConfigCount;

    /**
     * 取得表的配置
     * 取得表的配置
     *
     * @return
     */
//    @Override
//    public TISTable getTableConfig(String dbName, String tableName) {
//        JSONObject tableProfile = getGitJson(DATASOURCE_PROJECT_ID, TAB_CONFIG_ROOT_DIR + dbName + "/" + tableName + "/profile");
//        TISTable table = new TISTable();
//        table.setTableLogicName(tableProfile.getString("tableLogicName"));
//        table.setTableName(tableProfile.getString("tableName"));
//        table.setPartitionNum(tableProfile.getInt("partitionNum"));
//        table.setDbName(tableProfile.getString("dbName"));
//        table.setPartitionInterval(tableProfile.getInt("partitionInterval"));
//        final String keyReflectCols = "reflectCols";
//        if (!tableProfile.isNull(keyReflectCols)) {
//            JSONArray reflectCols = tableProfile.getJSONArray(keyReflectCols);
//            JSONObject col = null;
//            for (int i = 0; i < reflectCols.length(); i++) {
//                col = reflectCols.getJSONObject(i);
//                table.addColumnMeta(new ColumnMetaData(col.getInt("index"), col.getString("key"), col.getInt("type"), col.getBoolean("pk")));
//            }
//        }
//        FileContent f = getFileContent(DATASOURCE_PROJECT_ID, TAB_CONFIG_ROOT_DIR + dbName + "/" + tableName + "/sql", GitBranch.DEVELOP);
//        if (!f.exist()) {
//            throw new IllegalStateException("target file not exist:" + f);
//        }
//        table.setSelectSql(f.getContent());
//        ExecuteGetTableConfigCount++;
//        return table;
//    }

    /**
     * 线上配置
     *
     * @param db
     * @param commitLog
     */
    @Override
    public void createDatasourceFileOnline(TISDb db, String commitLog) {
        String dbName = db.getDbName();
        if (StringUtils.isEmpty(dbName)) {
            throw new IllegalArgumentException("param dbName can not be null");
        }
        this.createFile(getDBConfigPath(dbName, DbScope.DETAILED), GitBranchInfo.$(GitBranch.MASTER), db.createDBConfigDesc(), commitLog, DATASOURCE_PROJECT_ID, true);
    }

    /**
     * @param path
     * @param branch
     * @param dependencyTabs 逻辑表名稱
     * @param rule
     * @param commitLog
     */
    public void createWorkflowFile(String path, String branch, Set<String> dependencyTabs, JoinRule rule, String commitLog) {
        Assert.assertNotNull(path);
        Assert.assertNotNull(branch);
        Assert.assertTrue(!dependencyTabs.isEmpty());
        Assert.assertNotNull(rule);
        Assert.assertNotNull(commitLog);
        // 创建一个新的 和workflow同名的分支
        this.createWorkflowBarnch(branch);
        JSONObject profile = new JSONObject();
        profile.put("tables", dependencyTabs);
        this.createFile(path + "/profile", GitBranchInfo.$(branch), profile.toString(1), commitLog, WORKFLOW_GIT_PROJECT_ID, true);
        this.createFile(path + "/" + WF_FILE_NAME, GitBranchInfo.$(branch), rule.getContent(), commitLog, WORKFLOW_GIT_PROJECT_ID, true);
    }

    /**
     * 从git中取得workflow对象模型
     *
     * @param wfName 工作流名字
     * @return 工作流对象
     */
    public WorkflowPojo getWorkflow(String wfName, GitBranchInfo branch) {
        Assert.assertNotNull(wfName);
        Assert.assertNotNull(branch);
        WorkflowPojo workflow = new WorkflowPojo();
        workflow.setName(wfName);
        FileContent target = getFileContent(WORKFLOW_GIT_PROJECT_ID, wfName + "/" + WF_FILE_NAME, branch);
        if (!target.exist()) {
            throw new IllegalStateException("target file not exist:" + target.getFile());
        }
        workflow.setTask(new JoinRule(target.getContent()));
        return workflow;
    }

    public void updateWorkflowFile(String path, String branch, String content, String commitLog) {
        this.createFile(path, GitBranchInfo.$(branch), content, commitLog, WORKFLOW_GIT_PROJECT_ID, false);
    }

    public void deleteWorkflow(String name, GitUser user) {
        this.deleteFile(name, GitBranchInfo.$(GitBranch.MASTER), user, "delete workflow " + name, WORKFLOW_GIT_PROJECT_ID);
    }

    public static class JoinRule {

        private final String content;

        public JoinRule(String content) {
            super();
            this.content = content;
        }

        public String getContent() {
            return content;
        }
    }

    private void deleteFile(String path, GitBranchInfo branch, GitUser user, String commitLog, int projectId) {
        // String urlStromg = GIT_HOST + "/api/v4/projects/" + projectId + "/repository/files/" + this.urlEncode(path);
        // List<PostParam> params = new ArrayList<>();
        // params.add(new HttpUtils.PostParam("branch", branch.getBranch()));
        // params.add(new HttpUtils.PostParam("commit_message", commitLog));
        // params.add(new PostParam("author_name", user.getName()));
        // params.add(new PostParam("author_email", user.getEmail()));
        // HttpUtils.delete(urlStromg, params, gitPostStreamProcess);
    }

    private void createFile(String path, GitBranchInfo branch, String content, String commitLog, int projectId, boolean create) {
        processFile(path, branch, content, commitLog, projectId, (create ? HTTPMethod.POST : HTTPMethod.PUT));
    }

    /**
     * 更新/创建通用 <br/>
     * https://docs.gitlab.com/ee/api/repository_files.html
     *
     * @param path
     * @param branch
     * @param content
     * @param commitLog
     * @param projectId
     */
    private void processFile(String path, GitBranchInfo branch, String content, String commitLog, int projectId, HTTPMethod httpMethod) {
        try {
            File targetFile = new File(this.dbRootDir, path);
            FileUtils.writeStringToFile(targetFile, content, TisUTF8.get(), false);
            // return FileUtils.readFileToString(targetFile, TisUTF8.get());
        } catch (IOException e) {
            throw new RuntimeException("filepath:" + path, e);
        }
        // try {
        // URL urlString;
        // try {
        // urlString = new URL(GIT_HOST + "/api/v4/projects/" + projectId + "/repository/files/" + this.urlEncode(path));
        // } catch (MalformedURLException e1) {
        // throw new RuntimeException(e1);
        // }
        // List<PostParam> params = new ArrayList<>();
        // String branchName = StringUtils.isEmpty(branch.name) ? branch.staticName.value : branch.name;
        // params.add(new PostParam("branch", branchName));
        // params.add(new PostParam("encoding", "base64"));
        // params.add(new PostParam("author_email", "baisui@2dfire.com"));
        // params.add(new PostParam("author_name", "baisui"));
        // params.add(new PostParam("content", Base64.getEncoder().encodeToString(content.getBytes(Charset.forName("utf8")))));
        // params.add(new PostParam("commit_message", commitLog));
        // String result = HttpUtils.process(urlString, params, gitPostStreamProcess, httpMethod);
        // } catch (Exception e) {
        //
        // }
    }

    // private void updateFile(String path, String branch, String content,
    // String commitLog, int projectId) {
    // String urlString = "http://git.2dfire-inc.com/api/v4/projects/" +
    // projectId + "/repository/files";
    // List<PostParam> params = new ArrayList<>();
    // params.add(new PostParam("file_path", path));
    // params.add(new PostParam("branch_name", branch));
    // params.add(new PostParam("encoding", "base64"));
    // params.add(new PostParam("content",
    // Base64.getEncoder().encodeToString(content.getBytes(Charset.forName("utf8")))));
    // params.add(new PostParam("commit_message", commitLog));
    // 
    // String result = HttpUtils.put(urlString, params, new
    // GitPostStreamProcess<String>() {
    // @Override
    // public String p(int status, InputStream stream, String md5) {
    // try {
    // return IOUtils.toString(stream, "utf8");
    // } catch (IOException e) {
    // throw new RuntimeException(e);
    // }
    // }
    // });
    // // System.out.println(result);
    // }
    @Override
    public void updateDatasourceFileOnline(String path, String content, String commitLog) {
        this.createFile(path, GitBranchInfo.$(GitBranch.MASTER), content, commitLog, DATASOURCE_PROJECT_ID, false);
    }

    @Override
    public void deleteDb(String dbName, GitUser user) {
        File targetDir = new File(this.dbRootDir, getDBConfigParentPath(dbName));
        try {
            FileUtils.forceDelete(targetDir);
        } catch (IOException e) {
            throw new RuntimeException(targetDir.getAbsolutePath(), e);
        }
        // this.deleteFile(name + "/db_config", GitBranchInfo.$(GitBranch.MASTER), user, "delete db " + name, DATASOURCE_PROJECT_ID);
    }

    @Override
    public void deleteDbOnline(String name, GitUser user) {
        this.deleteDb(name, user);
    }

    private void deleteTable(String dbName, String tableLogicName, boolean isDaily, GitUser user) {
        String path = TAB_CONFIG_ROOT_DIR + "/" + dbName + "/" + tableLogicName;
        GitBranchInfo branch = isDaily ? GitBranchInfo.$(GitBranch.DEVELOP) : GitBranchInfo.$(GitBranch.MASTER);
        this.deleteFile(path + "/profile", branch, user, "delete table" + path, DATASOURCE_PROJECT_ID);
        this.deleteFile(path + "/sql", branch, user, "delete table" + path, DATASOURCE_PROJECT_ID);
    }

    @Override
    public void deleteTableDaily(String dbName, String tableLogicName, GitUser user) {
        this.deleteTable(dbName, tableLogicName, true, /* isDaily */
                user);
    }

    @Override
    public void deleteTableOnline(String dbName, String tableLogicName, GitUser user) {
        this.deleteTable(dbName, tableLogicName, false, user);
    }

    public void getProjects() {
        String urlString = GIT_HOST + "/api/v4/projects/owned";
        String result = HttpUtils.processContent(urlString, new GitStreamProcess<String>() {

            @Override
            public String p(int status, InputStream stream, Map<String, List<String>> headerFields) {
                try {
                    return IOUtils.toString(stream, "utf8");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        System.out.println(result);
    }

    private void getChildren() {
        final String urlString = GIT_HOST + "/api/v4/projects/1281/repository/tree?path=" + ("server") + "&ref_name=master";
        HttpUtils.processContent(urlString, new GitStreamProcess<String>() {

            @Override
            public String p(int status, InputStream stream, Map<String, List<String>> headerFields) {
                try {
                    return IOUtils.toString(stream, "utf8");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        // System.out.println(result);
    }

    public enum GitBranch {

        MASTER("master"), DEVELOP("develop");

        private final String value;

        private GitBranch(String value) {
            this.value = value;
        }
    }

    public static class GitBranchInfo {

        private final String name;

        private final GitBranch staticName;

        public GitBranchInfo(String name, GitBranch staticName) {
            super();
            this.name = name;
            this.staticName = staticName;
        }

        public String getBranch() {
            return staticName != null ? staticName.value : this.name;
        }

        public static GitBranchInfo $(String branchName) {
            if (StringUtils.isEmpty(branchName)) {
                throw new IllegalArgumentException("param branch can not be null");
            }
            return new GitBranchInfo(branchName, null);
        }

        public static GitBranchInfo $(GitBranch name) {
            return new GitBranchInfo(null, name);
        }
    }

    public static final class GitUser {

        public static GitUser dft() {
            return new GitUser("baisui", "baisui@2dfire.com");
        }

        private final String name;

        private final String email;

        public GitUser(String name, String email) {
            super();
            this.name = name;
            this.email = email;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }
    }

    private FileContent getFileContent(int projectId, String filePath, GitBranchInfo branch) {
        try {
            File targetFile = new File(this.dbRootDir, filePath);
            FileContent content = new FileContent(targetFile);
            if (this.fetchFromCenterRepository) {
                // 确保将远端文件保存到本地来
                CenterResource.copyFromRemote2Local(CenterResource.getPathURL(Config.SUB_DIR_CFG_REPO + "/" + DB_SUB_DIR, filePath), targetFile, true);
            }
            if (!targetFile.exists()) {
                if (this.fetchFromCenterRepository) {
                    throw new IllegalStateException("filePath:" + filePath + " is not exist");
                } else {
                    return content;
                }
            }
            return content.setContent(FileUtils.readFileToString(targetFile, TisUTF8.get()));
        } catch (IOException e) {
            throw new RuntimeException("filepath:" + filePath, e);
        }
    }

    public static class FileContent {

        private final File file;

        public FileContent(File file) {
            this.file = file;
        }

        public File getFile() {
            return file;
        }

        public boolean exist() {
            return this.content != null;
        }

        public String getContent() {
            return content;
        }

        public FileContent setContent(String content) {
            this.content = content;
            return this;
        }

        private String content;
    }

    private List<String> listChild(int projectId, String filePath, GitBranchInfo branch) {
        File path = new File(dbRootDir, filePath);
        if (this.fetchFromCenterRepository) {
            final String relativePath = CenterResource.getPath(DB_SUB_DIR, filePath);
            List<String> subFiles = CenterResource.getSubFiles(relativePath, false, true);
            for (String f : subFiles) {
                CenterResource.copyFromRemote2Local(CenterResource.getPath(relativePath, f), true);
            }
        }
        if (!path.exists()) {
            return Collections.emptyList();
        }
        return Lists.newArrayList(path.list());
        // String url = String.format(GIT_HOST + "/api/v4/projects/%d/repository/tree?ref=%s&path=%s&recursive=false", projectId,
        // branch.getBranch(), urlEncode(filePath));
        // return HttpUtils.processContent(url, new GitStreamProcess<List<String>>() {
        //
        // @Override
        // public List<String> p(int status, InputStream stream, Map<String, List<String>> headerFields) {
        // List<String> result = Lists.newArrayList();
        // JSONTokener tokener = new JSONTokener(stream);
        // JSONArray a = new JSONArray(tokener);
        // JSONObject o = null;
        //
        // for (int i = 0; i < a.length(); i++) {
        // o = a.getJSONObject(i);
        // result.add(o.getString("name"));
        // }
        // return result;
        // }
        // });
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, TisUTF8.getName());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    // 说明: https://docs.gitlab.com/ee/api/repository_files.html
    private FileContent getFileContent(int projectId, String filePath, GitBranch branch) {
        return this.getFileContent(projectId, filePath, GitBranchInfo.$(branch));
    }

    // private List<String> listChildFile(int projectId, String filePath, GitBranch branch) {
    // return this.getFileContent(projectId, filePath, GitBranchInfo.$(branch));
    // }

    /**
     * 取得Db配置
     *
     * @param dbName 数据库名
     * @return 数据库配置
     */
    @Override
    public DBConfig getDbLinkMetaData(String dbName, DbScope dbScope) {
        DBConfig dbConfig = getDbConfig(dbName, dbScope);
        return dbConfig;
    }

    // public DBConfig getDbLinkMetaData(String dbName, RunEnvironment runtime) {
    // DBConfig dbConfig = getDbConfig(dbName, runtime, DbScope.DETAILED);
    // return dbConfig;
    // }
    @Override
    public List<String> listDbConfigPath(String dbname) {
        // GitBranch branch = (runtime == RunEnvironment.DAILY) ? GitBranch.DEVELOP : GitBranch.MASTER;
        GitBranch branch = GitBranch.MASTER;
        return this.listChild(DATASOURCE_PROJECT_ID, getDBConfigParentPath(dbname), GitBranchInfo.$(branch));
    }

    @Override
    public boolean containFacadeDbTypeSubpath(String dbname) {
        List<String> child = this.listDbConfigPath(dbname);
        return child.contains(GitUtils.DB_CONFIG_META_NAME + DbScope.FACADE.getDBType());
    }

    // @Override
    public boolean isDbConfigExist(String dbName, DbScope dbScope) {
        return getDbConfigFile(dbName, dbScope).exist();
    }

    public FileContent getDbConfigFile(String dbName, DbScope dbScope) {
        if (StringUtils.isEmpty(dbName)) {
            throw new IllegalArgumentException("param dbName can not be null");
        }
        // GitBranch branch = (runtime == RunEnvironment.DAILY) ? GitBranch.DEVELOP : GitBranch.MASTER;
        GitBranch branch = GitBranch.MASTER;
        // 如果需要的是 facade的类型，但是没有，所以就取detailed的类型
        if (dbScope == DbScope.FACADE) {
            // targetFacade = true;
            if (!this.containFacadeDbTypeSubpath(dbName)) {
                dbScope = DbScope.DETAILED;
            }
        }
        // ////////////////////////////////////////////////////////////////////////
        FileContent f = getFileContent(DATASOURCE_PROJECT_ID, getDBConfigPath(dbName, dbScope), branch);
        return f;
    }

    @Override
    public DBConfig getDbConfig(String dbName, DbScope dbScope) {
        // if (StringUtils.isEmpty(dbName)) {
        // throw new IllegalArgumentException("param dbName can not be null");
        // }
        // //GitBranch branch = (runtime == RunEnvironment.DAILY) ? GitBranch.DEVELOP : GitBranch.MASTER;
        // GitBranch branch = GitBranch.MASTER;
        boolean targetFacade = (dbScope == DbScope.FACADE);
        // 如果需要的是 facade的类型，但是没有，所以就取detailed的类型
        // if (dbScope == DbScope.FACADE) {
        // targetFacade = true;
        // if (!this.containFacadeDbTypeSubpath(dbName)) {
        // dbScope = DbScope.DETAILED;
        // }
        // }
        // ////////////////////////////////////////////////////////////////////////
        // getFileContent(DATASOURCE_PROJECT_ID, getDBConfigPath(dbName, dbScope), branch);
        FileContent f = getDbConfigFile(dbName, dbScope);
        // final String content = getFileContent(DATASOURCE_PROJECT_ID, getDBConfigPath(dbName, runtime, dbScope), branch);
        if (!f.exist()) {
            throw new IllegalStateException("db:" + dbName + " can not fetch relevant db config,target file:" + f);
        }
        DBTokenizer tokenizer = new DBTokenizer(f.getContent());
        tokenizer.parse();
        DBConfigParser parser = new DBConfigParser(tokenizer.getTokenBuffer());
        DBConfig db = parser.startParser();
        if (targetFacade) {
            AtomicInteger hostCount = new AtomicInteger();
            AtomicReference<String> jdbcUrlRef = new AtomicReference<>();
            if (!db.vistDbURL(false, (r, jdbcUrl) -> {
                jdbcUrlRef.set(jdbcUrl);
                hostCount.incrementAndGet();
            }, dbScope == DbScope.FACADE, new AdapterMessageHandler(), new DefaultContext())) {
                throw new IllegalStateException("jdbcURL is illegal:" + jdbcUrlRef.get());
            }
            if (hostCount.get() != 1) {
                throw new IllegalStateException("facade db:" + dbName + " relevant hostCount can not big than 1,but now:" + hostCount);
            }
            // 最终想想还是不要在这里改db的名称
            // db.setName(dbName);
        }
        db.setPassword(Secret.decrypt(db.getPassword(), cryptKey));
        return db;
    }

    private JSONObject getGitJson(int gitProjectId, String gitPath) {
        return getGitJson(gitProjectId, gitPath, GitBranchInfo.$(GitBranch.DEVELOP));
    }

    private JSONObject getGitJson(int gitProjectId, String gitPath, GitBranchInfo branch) {
        FileContent target = getFileContent(gitProjectId, gitPath, branch);
        if (!target.exist()) {
            throw new IllegalStateException("target file is not exist" + target.getFile());
        }
        try {
            JSONTokener jsonTokener = new JSONTokener(target.getContent());
            return new JSONObject(jsonTokener);
        } catch (Exception e) {
            throw new IllegalStateException("projectid:" + gitProjectId + ",gitpath:" + gitPath + ",branch:" + branch.getBranch(), e);
        }
    }

    // private JSONObject getWorkflowGitJson(int gitProjectId, String gitPath,
    // GitBranch branch) {
    // try {
    // JSONTokener jsonTokener = new JSONTokener(getFileContent(gitProjectId,
    // gitPath, branch));
    // return new JSONObject(jsonTokener);
    // } catch (Exception e) {
    // throw new IllegalStateException("get workflow json file error", e);
    // }
    // }
    private String getFileContent(String urlString) {
        return HttpUtils.processContent(urlString, new GitStreamProcess<String>() {

            @Override
            public String p(int status, InputStream stream, Map<String, List<String>> headerFields) {
                JSONTokener tokener = new JSONTokener(stream);
                JSONObject o = new JSONObject(tokener);
                try {
                    return new String(Base64.getDecoder().decode(o.getString("content")), "utf8");
                } catch (Exception e) {
                    throw new RuntimeException(urlString, e);
                }
            }
        });
    }

    private String getGitUrlContent(String urlString) {
        return HttpUtils.processContent(urlString, new GitStreamProcess<String>() {

            @Override
            public String p(int status, InputStream stream, Map<String, List<String>> headerFields) {
                try {
                    return IOUtils.toString(stream, Charset.forName("UTF-8"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /**
     * description: 获取一个git仓库的所有变更记录 date: 10:44 AM 5/23/2017
     */
    public List<GitRepositoryCommitPojo> getGitRepositoryCommits(int projectId) throws ParseException {
        String urlString = GIT_HOST + "/api/v4/projects/" + projectId + "/repository/commits";
        String content = getGitUrlContent(urlString);
        List<GitRepositoryCommitPojo> commits = new LinkedList<>();
        JSONArray jsonArray = new JSONArray(content);
        for (Object object : jsonArray) {
            JSONObject jsonObject = (JSONObject) object;
            GitRepositoryCommitPojo pojo = new GitRepositoryCommitPojo(jsonObject);
            commits.add(pojo);
        }
        return commits;
    }

    public GitCommitVersionDiff getGitCommitVersionDiff(String fromVersion, String toVersion, int projectId) throws Exception {
        String urlString = String.format(GIT_HOST + "/api/v4/projects/%d/repository/compare?&from=%s&to=%s", projectId, fromVersion, toVersion);
        JSONObject jsonObject = new JSONObject(getGitUrlContent(urlString));
        GitCommitVersionDiff diff = new GitCommitVersionDiff();
        diff.setCommit(new GitRepositoryCommitPojo(jsonObject.getJSONObject("commit")));
        List<GitRepositoryCommitPojo> commits = new LinkedList<>();
        for (Object object : jsonObject.getJSONArray("commits")) {
            commits.add(new GitRepositoryCommitPojo((JSONObject) object));
        }
        diff.setCommits(commits);
        List<GitFileDiff> diffs = new LinkedList<>();
        for (Object object : jsonObject.getJSONArray("diffs")) {
            diffs.add(new GitFileDiff((JSONObject) object));
        }
        diff.setDiffs(diffs);
        diff.setCompareTimeout(jsonObject.getBoolean("compare_timeout"));
        diff.setCompareSameRef(jsonObject.getBoolean("compare_same_ref"));
        return diff;
    }

    private void createBranch(int projectId, String branchName, String ref) {
        String urlString = String.format(GIT_HOST + "/api/v4/projects/%d/repository/branches", projectId);
        List<PostParam> params = new ArrayList<>();
        params.add(new PostParam("branch_name", branchName));
        params.add(new PostParam("ref", ref));
        String result = HttpUtils.post(urlString, params, gitPostStreamProcess);
        System.out.println(result);
    }

    public void createWorkflowBarnch(String branchName) {
        this.createBranch(WORKFLOW_GIT_PROJECT_ID, branchName, "master");
    }

    /**
     * 删除一个分支
     *
     * @param projectId
     * @param branchName
     */
    private void deleteBranch(int projectId, String branchName) {
        String urlString = String.format(GIT_HOST + "/api/v4/projects/%d/repository/branches/%s", projectId, branchName);
        String result = HttpUtils.delete(urlString, null, gitPostStreamProcess);
        System.out.println(result);
    }

    /**
     * 删除工作流分支
     *
     * @param branchName
     */
    public void deleteWorkflowBranch(String branchName) {
        this.deleteBranch(WORKFLOW_GIT_PROJECT_ID, branchName);
    }

    /**
     * 创建一个merge请求
     *
     * @param projectId    项目id
     * @param sourceBranch 要merge的分支名字
     * @param targetBranch master
     * @param title        跟sourceBranch一样
     */
    private JSONObject createMergeRequest(int projectId, String sourceBranch, String targetBranch, String title, String description) {
        String urlString = String.format(GIT_HOST + "/api/v4/projects/%d/merge_requests", projectId);
        List<PostParam> params = new ArrayList<>();
        params.add(new PostParam("source_branch", sourceBranch));
        params.add(new PostParam("target_branch", targetBranch));
        params.add(new PostParam("title", title));
        if (!StringUtils.isBlank(description)) {
            params.add(new PostParam("description", description));
        }
        final String result = HttpUtils.post(urlString, params, gitPostStreamProcess);
        // });
        return new JSONObject(result);
    }

    /**
     * 接受一个merge请求
     *
     * @param projectId
     * @param mergeRequestId
     * @param mergeCommitMessage
     */
    private void acceptMergeRequest(int projectId, int mergeRequestId, String mergeCommitMessage) {
        final String urlString = String.format(GIT_HOST + "/api/v4/projects/%d/merge_request/%d/merge", projectId, mergeRequestId);
        List<PostParam> params = new ArrayList<>();
        if (!StringUtils.isBlank(mergeCommitMessage)) {
            params.add(new PostParam("merge_commit_message", mergeCommitMessage));
        }
        String result = HttpUtils.put(urlString, params, gitPostStreamProcess);
        System.out.println(result);
    }

    /**
     * 关闭一个MR
     *
     * @param projectId
     * @param mergeRequestId
     */
    private void closeMergeRequest(int projectId, int mergeRequestId) {
        String urlString = String.format(GIT_HOST + "/api/v4/projects/%d/merge_request/%d", projectId, mergeRequestId);
        List<PostParam> params = new ArrayList<>();
        params.add(new PostParam("state_event", "close"));
        String result = HttpUtils.put(urlString, params, gitPostStreamProcess);
        System.out.println(result);
    }

    /**
     * @param branchName
     */
    public void mergeWorkflowChange(String branchName) {
        // 1 先创建一个merge请求
        JSONObject result = this.createMergeRequest(WORKFLOW_GIT_PROJECT_ID, branchName, "master", branchName, "merge" + " workflow " + branchName);
        // 2 再接受这个merge请求
        int requestId = result.getInt("id");
        try {
            this.acceptMergeRequest(WORKFLOW_GIT_PROJECT_ID, requestId, "merge workflow " + branchName);
        } catch (Exception e) {
            // merge失败，可能有冲突，也可能没有发生变更，不管怎么样关闭这次MR
            this.closeMergeRequest(WORKFLOW_GIT_PROJECT_ID, requestId);
            throw new RuntimeException(e);
        }
        // 3 删除这个无用分支
        this.deleteWorkflowBranch(branchName);
        System.out.println(result);
    }

    // @Deprecated
    // private void updateWorkflowBranchReadme(String branchName) {
    // String file = GitUtils.$().getFileContent(WORKFLOW_GIT_PROJECT_ID,
    // "README.md", branchName);
    // file = file + "\n- update workflow" + branchName + " at " + new Date();
    // GitUtils.$().updateFile("README.md", branchName, file, "update readme",
    // WORKFLOW_GIT_PROJECT_ID);
    // }
    // private String getRawFileContent(int projectId, String sha, String filepath)
    // {
    // String urlString =
    // String.format("http://git.2dfire-inc.com/api/v4/projects/%d/repository/blobs/%s?filepath=%s",
    // projectId, sha, filepath);
    // return HttpUtils.processContent(urlString, new GitStreamProcess<String>() {
    // @Override
    // public String p(int status, InputStream stream, String md5) {
    // try {
    // return IOUtils.toString(stream, "UTF-8");
    // } catch (IOException e) {
    // throw new RuntimeException(e);
    // }
    // }
    // });
    // }
    // private JSONObject getRawFileJSON(int projectId, String sha, String filepath)
    // {
    // return new JSONObject(getRawFileContent(projectId, sha, filepath));
    // }
    // public WorkflowPojo getWorkflowSha(int projectId, String sha, String
    // filepath) {
    // JSONObject workflowJson = getRawFileJSON(projectId, sha, filepath);
    // WorkflowPojo gitWorkflowPojo = new WorkflowPojo();
    // gitWorkflowPojo.setName(workflowJson.getString("name"));
    // gitWorkflowPojo.setTask(workflowJson.getString("task"));
    // List<Integer> dependTableIds = new LinkedList<>();
    // for (String tableIdString : workflowJson.getString("tables").split(",")) {
    // dependTableIds.add(Integer.parseInt(StringUtils.trim(tableIdString)));
    // }
    // gitWorkflowPojo.setDependTableIds(dependTableIds);
    // return gitWorkflowPojo;
    // }
    private JSONArray getRepositoryTreeList(int projectId) {
        String urlString = String.format(GIT_HOST + "/api/v4/projects/%d/repository/tree", projectId);
        try {
            return new JSONArray(getGitUrlContent(urlString));
        } catch (Exception e) {
            throw new RuntimeException("获取git仓库树出错", e);
        }
    }

    public List<GitRepositoryTreeNode> getWorkflowRepositoryTreeList() {
        JSONArray jsonArray = getRepositoryTreeList(WORKFLOW_GIT_PROJECT_ID);
        List<GitRepositoryTreeNode> treeNodeList = new LinkedList<>();
        for (Object object : jsonArray) {
            GitRepositoryTreeNode node = new GitRepositoryTreeNode((JSONObject) object);
            treeNodeList.add(node);
        }
        return treeNodeList;
    }

    public String getLatestSha(int projectId) {
        List<GitRepositoryCommitPojo> gitRepositoryCommitPojos = null;
        try {
            gitRepositoryCommitPojos = this.getGitRepositoryCommits(projectId);
        } catch (ParseException e) {
            return null;
        }
        if (CollectionUtils.isEmpty(gitRepositoryCommitPojos)) {
            return null;
        }
        return gitRepositoryCommitPojos.get(0).getId();
    }

    private abstract static class GitPostStreamProcess<T> extends PostFormStreamProcess<T> {

        @Override
        public final List<ConfigFileContext.Header> getHeaders() {
            List<ConfigFileContext.Header> heads = new ArrayList<>();
            heads.add(PRIVATE_TOKEN);
            heads.addAll(super.getHeaders());
            return heads;
            // return createHeaders(super.getHeaders());
        }
    }

    private abstract static class GitStreamProcess<T> extends StreamProcess<T> {

        @Override
        public final List<ConfigFileContext.Header> getHeaders() {
            List<ConfigFileContext.Header> heads = new ArrayList<>();
            heads.add(PRIVATE_TOKEN);
            heads.addAll(super.getHeaders());
            return heads;
        }
    }
    // public static String encodingPassword(String password) {
    // char[] a = password.toCharArray();
    // for (int i = 0; i < a.length; i++) {
    // a[i] = (char) (a[i] ^ 't');
    // }
    // return new String(a);
    // }
}
