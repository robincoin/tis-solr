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
package com.qlangtech.tis.offline.module.action;

import com.alibaba.citrus.turbine.Context;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.koubei.web.tag.pager.Pager;
import com.qlangtech.tis.TIS;
import com.qlangtech.tis.assemble.FullbuildPhase;
import com.qlangtech.tis.common.utils.Assert;
import com.qlangtech.tis.coredefine.module.action.CoreAction;
import com.qlangtech.tis.db.parser.domain.DBConfig;
import com.qlangtech.tis.db.parser.domain.DBConfigSuit;
import com.qlangtech.tis.extension.Descriptor;
import com.qlangtech.tis.extension.DescriptorExtensionList;
import com.qlangtech.tis.fullbuild.IFullBuildContext;
import com.qlangtech.tis.git.GitUtils;
import com.qlangtech.tis.git.GitUtils.JoinRule;
import com.qlangtech.tis.manage.PermissionConstant;
import com.qlangtech.tis.manage.common.AppDomainInfo;
import com.qlangtech.tis.manage.common.HttpUtils.PostParam;
import com.qlangtech.tis.manage.common.IUser;
import com.qlangtech.tis.manage.common.Option;
import com.qlangtech.tis.manage.spring.aop.Func;
import com.qlangtech.tis.offline.DbScope;
import com.qlangtech.tis.offline.module.manager.impl.OfflineManager;
import com.qlangtech.tis.offline.pojo.GitRepositoryCommitPojo;
import com.qlangtech.tis.offline.pojo.TISDb;
import com.qlangtech.tis.offline.pojo.WorkflowPojo;
import com.qlangtech.tis.plugin.DataSourceFactoryPluginStore;
import com.qlangtech.tis.plugin.PluginStore;
import com.qlangtech.tis.plugin.annotation.Validator;
import com.qlangtech.tis.plugin.ds.*;
import com.qlangtech.tis.runtime.module.action.BasicModule;
import com.qlangtech.tis.runtime.module.misc.IControlMsgHandler;
import com.qlangtech.tis.runtime.module.misc.IFieldErrorHandler;
import com.qlangtech.tis.runtime.module.misc.impl.DelegateControl4JsonPostMsgHandler;
import com.qlangtech.tis.sql.parser.SqlTaskNode;
import com.qlangtech.tis.sql.parser.SqlTaskNodeMeta;
import com.qlangtech.tis.sql.parser.SqlTaskNodeMeta.SqlDataFlowTopology;
import com.qlangtech.tis.sql.parser.er.ERRules;
import com.qlangtech.tis.sql.parser.er.PrimaryTableMeta;
import com.qlangtech.tis.sql.parser.er.TabCardinality;
import com.qlangtech.tis.sql.parser.er.TableRelation;
import com.qlangtech.tis.sql.parser.exception.TisSqlFormatException;
import com.qlangtech.tis.sql.parser.meta.*;
import com.qlangtech.tis.util.DescriptorsJSON;
import com.qlangtech.tis.workflow.dao.IComDfireTisWorkflowDAOFacade;
import com.qlangtech.tis.workflow.dao.IWorkFlowDAO;
import com.qlangtech.tis.workflow.pojo.DatasourceTable;
import com.qlangtech.tis.workflow.pojo.WorkFlow;
import com.qlangtech.tis.workflow.pojo.WorkFlowCriteria;
import name.fraser.neil.plaintext.diff_match_patch;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.qlangtech.tis.sql.parser.er.ERRules.$;
import static java.sql.Types.*;

/**
 * 离线相关配置更新
 * <ul>
 * <li>datasource</li>
 * <li>table</li>
 * </ul>
 *
 * @author 百岁（baisui@qlangtech.com）
 * @date 2019年7月25日
 */
public class OfflineDatasourceAction extends BasicModule {

  private static final long serialVersionUID = 1L;

  private OfflineManager offlineManager;

  private static final int COMMITS_LIMIT = 20;

  private static final Pattern DB_ENUM_PATTERN = Pattern.compile("(\\w+?)(\\d+)");

  private static final Pattern WORD_CHARACTER_PATTERN = Pattern.compile("\\w*");

  private static final diff_match_patch DIFF_MATCH_PATCH = new diff_match_patch();

  private IComDfireTisWorkflowDAOFacade offlineDAOFacade;

  private static boolean isWordCharacter(String word) {
    return WORD_CHARACTER_PATTERN.matcher(word).matches();
  }

  @Autowired
  public void setOfflineManager(OfflineManager offlineManager) {
    this.offlineManager = offlineManager;
  }

  public void doTestTransaction(Context context) {
    WorkFlow wf = new WorkFlow();
    wf.setCreateTime(new Date());
    wf.setGitPath("gitpath");
    wf.setName("baisuitest");
    wf.setOpUserId(123);
    wf.setOpUserName("baisui");
    wf.setOpTime(new Date());
    this.offlineDAOFacade.getWorkFlowDAO().insertSelective(wf);
  }

  /**
   * @param context
   * @throws Exception
   */
  public void doGetWorkflowId(Context context) throws Exception {
    AppDomainInfo app = this.getAppDomain();
    if (app.getApp().getWorkFlowId() == null) {
      throw new IllegalStateException("WorkFlowId have not been set in collection:" + app.getAppName());
    }
    Map<String, Integer> result = Maps.newHashMap();
    result.put("workflowId", app.getApp().getWorkFlowId());
    this.setBizResult(context, result);
  }

  /**
   * 表添加页面，DB控件选择变化
   *
   * @param context
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doSelectDbChange(Context context) throws Exception {
    Integer dbid = this.getInt("dbid");
    com.qlangtech.tis.workflow.pojo.DatasourceDb db = this.offlineDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbid);
    PluginStore<DataSourceFactory> dbPlugin = TIS.getDataBasePluginStore(this, new PostedDSProp(db.getName(), DbScope.DETAILED));

    List<String> tabs = dbPlugin.getPlugin().getTablesInDB();
    // 通过DB的连接信息找到找到db下所有表信息
    // 对应的tabs列表
    this.setBizResult(context, tabs.stream().map((t) -> {
      return new Option(t, t);
    }).collect(Collectors.toList()));
  }

  /**
   * 添加数据库
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doAddDatasourceDb(Context context) throws Exception {
    // 1. 先校验输入合法性
    TISDb dbPojo = getDb(context, true);
    if (dbPojo == null) {
      return;
    }
    // 2. 测试数据库连接
    // if (!testDbConnection(dbPojo, context)) {
    // return;
    // }
    // 3. 添加数据库
    //  offlineManager.addDb(dbPojo.getDbName(), this, context);
    // 4. 返回最新的数据源信息
  }

  /**
   * 校验数据连接是否正常
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doVerifyDbConfigAdd(Context context) throws Exception {
    this.verifyDbConfig(context, true);
  }

  /**
   * 更新模式下校验
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doVerifyDbConfigUpdate(Context context) throws Exception {
    this.verifyDbConfig(context, false);
  }

  private void verifyDbConfig(Context context, boolean isNew) throws Exception {
    TISDb dbConfig = getDb(context, isNew);
    if (dbConfig == null) {
      return;
    }
    OfflineManager.TestDbConnection verifyResult = this.offlineManager.testDbConnection(dbConfig, this, context);
    if (verifyResult.valid) {
      this.addActionMessage(context, dbConfig.isFacade() ? "数据库连接正常" : "子数据库" + verifyResult.getDbCount() + "个连接正常");
    }
  }

  /**
   * 更新facade数据库配置
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doEditFacadeDb(Context context) throws Exception {
    this.processFacadeDB(context, false);
  }

  /**
   * 添加门面数据源
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doAddFacadeDb(Context context) throws Exception {
    this.processFacadeDB(context, true);
  }

  private void processFacadeDB(Context context, boolean isNew) throws Exception {
    // 1. 先校验输入合法性
    TISDb db = getDb(context, isNew);
    if (db == null) {
      return;
    }
    db.setFacade(true);
    offlineManager.updateFacadeDBConfig(Integer.parseInt(db.getDbId()), db, this, context);
    db.setPassword("******");
    this.setBizResult(context, db.getDbId());
    // 4. 返回最新的数据源信息
    this.addActionMessage(context, "添加数据源成功");
  }

  /**
   * 修改db配置
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doEditDatasourceDb(Context context) throws Exception {
    // 1. 先校验输入合法性
    TISDb dbPojo = getDb(context, false);
    if (dbPojo == null) {
      return;
    }
    // Integer dbId = this.getInt("id");
    // 3. 更新git
    offlineManager.editDatasourceDb(dbPojo, this, context);
    // 4. 需要把之前所有的dump全部失效
    // offlineManager.disableDbDump(dbId, this, context);
    this.addActionMessage(context, "数据源修改成功");
  }

  static {
    try {
      Class.forName("com.mysql.jdbc.Driver");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private class DbEnumShow implements Comparable<DbEnumShow> {

    private String dbName;

    private String host;

    DbEnumShow(String dbName, String host) {
      this.dbName = dbName;
      this.host = host;
    }

    public String getDbName() {
      return dbName;
    }

    public String getHost() {
      return host;
    }

    @Override
    public int compareTo(DbEnumShow o) {
      String dbName = this.getDbName();
      String anotherDbName = o.getDbName();
      if (dbName.length() == anotherDbName.length()) {
        return this.getDbName().compareTo(o.getDbName());
      } else {
        return dbName.length() - anotherDbName.length();
      }
    }
  }

  /**
   * Do getUsableDbNames dbs. 获取工作流里可用的数据库
   *
   * @param context the context
   */
  public void doGetUsableDbNames(Context context) {
    this.setBizResult(context, offlineManager.getUsableDbNames());
  }

  private TISDb getDb(Context context, boolean isNew) throws Exception {
    this.errorsPageShow(context);
    TISDb pojo = new TISDb();
    Map<String, Validator.FieldValidators> validateRule = //
      Validator.fieldsValidator("dbName", new Validator.FieldValidators(Validator.require, Validator.identity) {

        @Override
        public void setFieldVal(String val) {
          pojo.setDbName(val);
        }
      }, "encoding", new Validator.FieldValidators(Validator.require) {

        @Override
        public void setFieldVal(String val) {
          pojo.setEncoding(val);
        }
      }, "dbType", new Validator.FieldValidators(Validator.require) {

        @Override
        public void setFieldVal(String val) {
          pojo.setDbType(val);
        }
      }, "userName", new Validator.FieldValidators(Validator.require) {

        @Override
        public void setFieldVal(String val) {
          pojo.setUserName(val);
        }
      }, "port", new Validator.FieldValidators(Validator.require, Validator.integer) {

        @Override
        public void setFieldVal(String val) {
          pojo.setPort(val);
        }
      }, "host", new Validator.FieldValidators(Validator.require) {

        @Override
        public void setFieldVal(String val) {
          pojo.setHost(val);
        }
      });
    this.addPasswordValidator(isNew, pojo, validateRule);
    IControlMsgHandler handler = new DelegateControl4JsonPostMsgHandler(this, this.parseJsonPost());
    Boolean facade = null;
    try {
      facade = handler.getBoolean("facade");
    } catch (Exception e) {
      throw new IllegalArgumentException("param facade is illegal");
    }
    if (!isNew || facade) {
      validateRule.put("dbId", new Validator.FieldValidators(Validator.require) {

        @Override
        public void setFieldVal(String val) {
          pojo.setDbId(val);
        }
      });
    }
    if (!Validator.validate(handler, context, validateRule)) {
      return null;
    }
    DbScope dbScope = facade ? DbScope.FACADE : DbScope.DETAILED;
    if (!isNew && StringUtils.isEmpty(pojo.getPassword())) {
      // 更新流程下需要将系统中已经保存的密码取回
      com.qlangtech.tis.workflow.pojo.DatasourceDb db = this.offlineManager.getDB(Integer.parseInt(pojo.getDbId()));
      DBConfig dbConfig = null;
      if (GitUtils.$().isDbConfigExist(db.getName(), dbScope) && StringUtils.equals((dbConfig = GitUtils.$().getDbLinkMetaData(db.getName(), dbScope)).getName(), pojo.getDbName())) {
        pojo.setPassword(dbConfig.getPassword());
      } else if (StringUtils.isBlank(pojo.getPassword())) {
        this.addPasswordValidator(true, pojo, validateRule);
        if (!Validator.validate(handler, context, validateRule)) {
          return null;
        }
      }
    }
    String extraParams = handler.getString("extraParams");
    String shardingType = handler.getString("shardingType");
    String shardingEnum = handler.getString("shardingEnum");
    pojo.setFacade(facade);
    pojo.setExtraParams(extraParams);
    pojo.setShardingType(shardingType);
    pojo.setShardingEnum(shardingEnum);
    return pojo;
  }

  private void addPasswordValidator(boolean isNew, TISDb pojo, Map<String, Validator.FieldValidators> validateRule) {
    Validator[] validators = isNew ? new Validator[]{Validator.require} : new Validator[0];
    validateRule.put("password", new Validator.FieldValidators(validators) {

      @Override
      public void setFieldVal(String val) {
        pojo.setPassword(val);
      }
    });
  }

  private TISTable getTablePojo(Context context) {
    com.alibaba.fastjson.JSONObject form = this.parseJsonPost();
    final boolean updateMode = !form.getBoolean("isAdd");
    String tableName = form.getString("tableName");
    if (StringUtils.isBlank(tableName)) {
      this.addErrorMessage(context, "表名不能为空");
      return null;
    }
    if (!isWordCharacter(tableName)) {
      this.addErrorMessage(context, "表名必须由英文字符，数字和下划线组成");
      return null;
    }
    String tableLogicName = tableName;
    Integer partitionNum = form.getIntValue("partitionNum");
    if (partitionNum == null) {
      this.addErrorMessage(context, "分区数不能为空");
      return null;
    }
    Integer dbId = form.getIntValue("dbId");
    if (dbId == null) {
      this.addErrorMessage(context, "数据库不能为空");
      return null;
    }
    Integer partitionInterval = form.getIntValue("partitionInterval");
    if (partitionInterval == null) {
      this.addErrorMessage(context, "分区间隔不能为空");
      return null;
    }
    String selectSql = form.getString("selectSql");
    if (StringUtils.isBlank(selectSql) || StringUtils.contains(selectSql, "*")) {
      this.addErrorMessage(context, "sql语句不能为空且不能包含*");
      return null;
    }
    TISTable tab = new TISTable(tableLogicName, tableName, partitionNum, dbId, partitionInterval, selectSql);
    com.alibaba.fastjson.JSONArray cols = form.getJSONArray("cols");
    com.alibaba.fastjson.JSONObject col = null;
    ColumnMetaData colMeta = null;
    for (int i = 0; i < cols.size(); i++) {
      col = cols.getJSONObject(i);
      colMeta = new ColumnMetaData(i, col.getString("key"), col.getIntValue("type"), col.getBoolean("pk"));
      // tab.addColumnMeta(colMeta);
    }
    if (updateMode) {
      tab.setTabId(form.getInteger("tabId"));
    }
    tab.setDbName(offlineDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId).getName());
    return tab;
  }

  /**
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doAddDatasourceTable(Context context) throws Exception {
    this.errorsPageShow(context);
    // 1. 校验合法性
    TISTable pojo = getTablePojo(context);
    if (pojo == null) {
      return;
    }
    // 2. 添加表
    offlineManager.addDatasourceTable(pojo, this, context, pojo.getTabId() != null);
  }

  // /**
  // * 修改table配置
  // *
  // * @param context
  // * @throws Exception
  // */
  // public void doEditDatasourceTable(Context context) throws Exception {
  // // 1. 先校验输入合法性
  // TISTable pojo = getTablePojo(context);
  // if (pojo == null) {
  // return;
  // }
  // Integer tableId = this.getInt("id");
  // if (tableId == null) {
  // this.addErrorMessage(context, "table id 不能为空");
  // return;
  // }
  // // 3. 更新git
  // offlineManager.editDatasourceTable(pojo, this, context);
  // // 4. 需要把之前所有的dump全部失效
  // offlineManager.disableTableDump(tableId, this, context);
  // this.addActionMessage(context, "table修改成功");
  // }

  /**
   * Do get git commit logs. 获得一个仓库的提交历史
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doGetGitCommitLogs(Context context) throws Exception {
    String directory = this.getString("directory");
    int projectId;
    if (StringUtils.equals("datasource_daily", directory)) {
      projectId = GitUtils.DATASOURCE_PROJECT_ID;
    } else if (StringUtils.equals("datasource_online", directory)) {
      projectId = GitUtils.DATASOURCE_PROJECT_ID;
    } else if (StringUtils.equals("workflow", directory)) {
      projectId = GitUtils.WORKFLOW_GIT_PROJECT_ID;
    } else {
      throw new RuntimeException("directory = " + directory + " is wrong!");
    }
    List<GitRepositoryCommitPojo> commits = GitUtils.$().getGitRepositoryCommits(projectId);
    while (commits.size() > COMMITS_LIMIT) {
      commits.remove(commits.size() - 1);
    }
    this.setBizResult(context, commits);
  }

  // /**
  // * Do get commit version diff. 获取两个版本的变更记录
  // *
  // * @param context the context
  // * @throws Exception the exception
  // */
  // public void doGetCommitVersionDiff(Context context) throws Exception {
  // String fromVersion = this.getString("fromVersion");
  // String toVersion = this.getString("toVersion");
  // if (StringUtils.isBlank(fromVersion) || fromVersion.length() != 40) {
  // this.addErrorMessage(context, "fromVersion版本号错误");
  // return;
  // }
  // if (StringUtils.isBlank(toVersion) || toVersion.length() != 40) {
  // this.addErrorMessage(context, "toVersion版本号错误");
  // return;
  // }
  // String directory = this.getString("directory");
  // int projectId;
  // if (StringUtils.equals("datasource_daily", directory)) {
  // projectId = GitUtils.DATASOURCE_PROJECT_ID;
  // } else if (StringUtils.equals("datasource_online", directory)) {
  // projectId = GitUtils.DATASOURCE_PROJECT_ID;
  // } else if (StringUtils.equals("workflow", directory)) {
  // projectId = GitUtils.WORKFLOW_GIT_PROJECT_ID;
  // } else {
  // throw new RuntimeException("directory = " + directory + " is wrong!");
  // }
  // GitCommitVersionDiff diff = GitUtils.$().getGitCommitVersionDiff(fromVersion, toVersion, projectId);
  // this.setBizResult(context, diff);
  // }

  /**
   * Do get workflows. 获取去数据库查找所有工作流
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doGetWorkflows(Context context) throws Exception {
    Pager pager = createPager();
    IWorkFlowDAO wfDAO = this.getWorkflowDAOFacade().getWorkFlowDAO();
    WorkFlowCriteria query = new WorkFlowCriteria();
    query.createCriteria();
    query.setOrderByClause("id desc");
    pager.setTotalCount(wfDAO.countByExample(query));
    this.setBizResult(context, new PaginationResult(pager, wfDAO.selectByExample(query, pager.getCurPage(), pager.getRowsPerPage())));
  }

  public void doGetWorkflowTopology(Context context) throws Exception {
    final String topology = this.getString("topology");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalStateException("please set param topology");
    }
    SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    this.setBizResult(context, wfTopology);
  }

  /**
   * 取得已经保存的erRules
   *
   * @param context
   * @throws Exception
   */
  public void doGetWorkflowTopologyERRule(Context context) throws Exception {
    final String topology = this.getString("topology");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalStateException("please set param topology");
    }
    SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    this.setBizResult(context, wfTopology.getDumpNodes());
  }

  @Func(PermissionConstant.DATAFLOW_UPDATE)
  public void doUpdateTopology(Context context) throws Exception {
    this.doUpdateTopology(context, (topologyName, topology) -> {
      SqlTaskNodeMeta.TopologyProfile profile = topology.getProfile();
      if (profile.getDataflowId() < 1) {
        profile.setDataflowId(this.getWorkflowId(topologyName));
      }
    });
  }

  /**
   * WorkflowAddJoinComponent 点击保存按钮进行，服务端进行校验
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.DATAFLOW_MANAGE, sideEffect = false)
  public void doValidateWorkflowAddJoinComponentForm(Context context) throws Exception {
    com.alibaba.fastjson.JSONObject form = this.parseJsonPost();

    IControlMsgHandler handler = new DelegateControl4JsonPostMsgHandler(this, form);

    String sql = form.getString("sql");
    String exportName = form.getString("exportName");
    com.alibaba.fastjson.JSONArray dependenceNodes = form.getJSONArray("dependencies");
    final List<String> dependencyNodes = Lists.newArrayList();
    final String validateRuleDependency = "dependencies";
    Map<String, Validator.FieldValidators> validateRule = //
      Validator.fieldsValidator(//
        "sql" //
        , new Validator.FieldValidators(Validator.require) {
          // 添加校验依赖
        }.addDependency(validateRuleDependency), //
        new Validator.IFieldValidator() {
          @Override
          public boolean validate(IFieldErrorHandler msgHandler, Context context, String fieldKey, String fieldData) {
            Optional<TisSqlFormatException> sqlErr = SqlTaskNodeMeta.validateSql(fieldData, dependencyNodes);
            if (sqlErr.isPresent()) {
              msgHandler.addFieldError(context, fieldKey, sqlErr.get().summary());
              return false;
            }
            return true;
          }
        }, //
        "exportName" //
        , new Validator.FieldValidators(Validator.require, Validator.identity) {
        }, //
        validateRuleDependency //
        , new Validator.FieldValidators(Validator.require) {
          @Override
          public void setFieldVal(String val) {
            com.alibaba.fastjson.JSONArray dpts = JSON.parseArray(val);
            com.alibaba.fastjson.JSONObject o = null;
            for (int index = 0; index < dpts.size(); index++) {
              o = dpts.getJSONObject(index);
              dependencyNodes.add(o.getString("label"));
            }
          }
        },
        new Validator.IFieldValidator() {
          @Override
          public boolean validate(IFieldErrorHandler msgHandler, Context context, String fieldKey, String fieldData) {
            com.alibaba.fastjson.JSONArray dpts = JSON.parseArray(fieldData);
            if (dpts.size() < 1) {
              msgHandler.addFieldError(context, fieldKey, "请选择依赖表节点");
              return false;
            }
            return true;
          }
        });

    if (!Validator.validate(handler, context, validateRule)) {
      // return;
    }

  }

  private void doUpdateTopology(Context context, TopologyUpdateCallback dbSaver) throws Exception {
    final String content = IOUtils.toString(this.getRequest().getInputStream(), getEncode());
    JSONTokener tokener = new JSONTokener(content);
    JSONObject topology = new JSONObject(tokener);
    final String topologyName = topology.getString("topologyName");
    if (StringUtils.isEmpty(topologyName)) {
      this.addErrorMessage(context, "请填写数据流名称");
      return;
    }
    File parent = new File(SqlTaskNode.parent, topologyName);
    FileUtils.forceMkdir(parent);
    JSONArray nodes = topology.getJSONArray("nodes");
    // 这部分信息暂时先不需要了，已经包含在‘nodes’中了
    JSONArray edges = topology.getJSONArray("edges");
    JSONObject o = null;
    JSONObject nodeMeta = null;
    JSONObject nestNodeMeta = null;
    JSONArray joinDependencies = null;
    JSONObject dep = null;
    NodeType nodetype = null;
    DependencyNode dnode = null;
    SqlTaskNodeMeta pnode = null;
    Position pos = null;
    final SqlDataFlowTopology topologyPojo = new SqlDataFlowTopology();
    SqlTaskNodeMeta.TopologyProfile profile = new SqlTaskNodeMeta.TopologyProfile();
    profile.setName(topologyName);
    profile.setTimestamp(System.currentTimeMillis());
    // profile.setDataflowId(this.getWorkflowId(topologyName));
    topologyPojo.setProfile(profile);
    int x, y;
    int tableid;
    Tab tab;
    for (int i = 0; i < nodes.length(); i++) {
      o = nodes.getJSONObject(i);
      x = o.getInt("x");
      if (x < 0) {
        // 如果在边界外的图形需要跳过`
        continue;
      }
      y = o.getInt("y");
      pos = new Position();
      pos.setX(x);
      pos.setY(y);
      nodeMeta = o.getJSONObject("nodeMeta");
      nestNodeMeta = nodeMeta.getJSONObject("nodeMeta");
      nodetype = NodeType.parse(nestNodeMeta.getString("type"));
      if (nodetype == NodeType.DUMP) {
        dnode = new DependencyNode();
        dnode.setExtraSql(SqlTaskNodeMeta.processBigContent(nodeMeta.getString("sqlcontent")));
        dnode.setId(o.getString("id"));
        tableid = nodeMeta.getInt("tabid");
        Map<Integer, com.qlangtech.tis.workflow.pojo.DatasourceDb> dbMap = Maps.newHashMap();
        tab = getDatabase(dbMap, tableid);
        dnode.setDbName(tab.db.getName());
        dnode.setName(tab.tab.getTableLogicName());
        dnode.setTabid(String.valueOf(tableid));
        dnode.setDbid(String.valueOf(nodeMeta.get("dbid")));
        dnode.setPosition(pos);
        dnode.setType(NodeType.DUMP.getType());
        topologyPojo.addDumpTab(dnode);
      } else if (nodetype == NodeType.JOINER_SQL) {
        pnode = new SqlTaskNodeMeta();
        pnode.setPosition(pos);
        pnode.setSql(SqlTaskNodeMeta.processBigContent(nodeMeta.getString("sql")));
        pnode.setExportName(nodeMeta.getString("exportName"));
        // pnode.setId(String.valueOf(nodeMeta.get("id")));
        pnode.setId(o.getString("id"));
        pnode.setType(NodeType.JOINER_SQL.getType());
        joinDependencies = nodeMeta.getJSONArray("dependencies");
        for (int k = 0; k < joinDependencies.length(); k++) {
          dep = joinDependencies.getJSONObject(k);
          dnode = new DependencyNode();
          dnode.setId(dep.getString("value"));
          dnode.setName(dep.getString("label"));
          dnode.setType(NodeType.DUMP.getType());
          pnode.addDependency(dnode);
        }
        topologyPojo.addNodeMeta(pnode);
      } else {
        throw new IllegalStateException("nodetype:" + nodetype + " is illegal");
      }
    }
    // 校验一下是否只有一个最终输出节点
    List<SqlTaskNodeMeta> finalNodes = topologyPojo.getFinalNodes();
    if (finalNodes.size() > 1) {
      this.addErrorMessage(context, "最终输出节点(" + finalNodes.stream().map((r) -> r.getExportName()).collect(Collectors.joining(",")) + ")不能多于一个");
      return;
    }
    if (finalNodes.size() < 1) {
      this.addErrorMessage(context, "请定义数据处理节点");
      return;
    }
    Optional<ERRules> erRule = ERRules.getErRule(topologyPojo.getName());
    this.setBizResult(context, new ERRulesStatus(erRule));
    dbSaver.execute(topologyName, topologyPojo);
    // 保存一个时间戳
    SqlTaskNodeMeta.persistence(topologyPojo, parent);
    // 备份之用
    FileUtils.write(new File(parent, topologyName + "_content.json"), content, getEncode(), false);
    this.addActionMessage(context, "'" + topologyName + "'保存成功");
  }

  public static class ERRulesStatus {

    private final ERRules erRules;

    private boolean exist;

    public ERRulesStatus(Optional<ERRules> erRules) {
      if (this.exist = erRules.isPresent()) {
        this.erRules = erRules.get();
      } else {
        this.erRules = null;
      }
    }

    public boolean isErExist() {
      return this.exist;
    }

    public boolean isErPrimaryTabSet() {
      if (!isErExist()) {
        return false;
      }
      return this.erRules.getPrimaryTabs().size() > 0;
    }
  }

  private Integer getWorkflowId(String topologyName) {
    // return SqlTaskNodeMeta.getTopologyProfile(topologyName).getDataflowId();
    WorkFlowCriteria wc = new WorkFlowCriteria();
    wc.createCriteria().andNameEqualTo(topologyName);
    List<WorkFlow> workFlows = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByExample(wc);
    for (WorkFlow wf : workFlows) {
      // topologyPojo.setDataflowId(wf.getId());
      return wf.getId();
    }
    throw new IllegalStateException("topology:" + topologyName + " can not find workflow record in db");
  }

  private Tab getDatabase(Map<Integer, com.qlangtech.tis.workflow.pojo.DatasourceDb> dbMap, int tableid) {
    Tab dtab = null;
    DatasourceTable tab = this.getWorkflowDAOFacade().getDatasourceTableDAO().selectByPrimaryKey(tableid);
    if (tab == null) {
      throw new IllegalStateException("tabid:" + tableid + " relevant 'TableDump' object can not be null");
    }
    dtab = new Tab(tab);
    com.qlangtech.tis.workflow.pojo.DatasourceDb db = null;
    if ((db = dbMap.get(tab.getDbId())) == null) {
      db = this.getWorkflowDAOFacade().getDatasourceDbDAO().selectByPrimaryKey(tab.getDbId());
      if (db == null) {
        throw new IllegalStateException("tabid:" + tableid + " relevant 'TableDump' object can not be null");
      }
      dbMap.put(tab.getDbId(), db);
    }
    dtab.setDb(db);
    return dtab;
  }

  private static class Tab {

    private com.qlangtech.tis.workflow.pojo.DatasourceDb db;

    private final DatasourceTable tab;

    public Tab(DatasourceTable tab) {
      super();
      this.tab = tab;
    }

    public com.qlangtech.tis.workflow.pojo.DatasourceDb getDb() {
      return db;
    }

    public void setDb(com.qlangtech.tis.workflow.pojo.DatasourceDb db) {
      this.db = db;
    }

    public DatasourceTable getTab() {
      return tab;
    }
  }

  /**
   * 取得ER关系规则
   *
   * @param context
   * @throws Exception
   */
  public void doGetErRule(Context context) throws Exception {
    String topology = this.getString("topology");
    // 强制同步,时间长了，toplolog上会新加一些数据表，导致ER规则和现有topology不同步，需要同步一下
    boolean forceSync = this.getBoolean("sync");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalArgumentException("param 'topology' can not be null");
    }
    ERRules erRule = null;
    if (!ERRules.ruleExist(topology)) {
      // 还没有创建ER规则
      erRule = new ERRules();
      for (DependencyNode node : getDumpNodesFromTopology(topology)) {
        erRule.addDumpNode(node);
      }
    } else {
      erRule = ERRules.getErRule(topology).get();
      final List<DependencyNode> oldErRules = erRule.getDumpNodes();
      if (forceSync) {
        List<DependencyNode> dumpNodes = getDumpNodesFromTopology(topology);
        /**
         * *************************************
         * 需要添加的节点
         * ***************************************
         */
        List<DependencyNode> shallBeAdd = dumpNodes.stream().filter((n) -> !oldErRules.stream().filter((old) -> StringUtils.equals(n.getTabid(), old.getTabid())).findAny().isPresent()).collect(Collectors.toList());
        /**
         * *************************************
         * 需要删除的节点
         * ***************************************
         */
        Iterator<DependencyNode> it = oldErRules.iterator();
        while (it.hasNext()) {
          DependencyNode old = it.next();
          if (!dumpNodes.stream().filter((n) -> StringUtils.equals(n.getTabid(), old.getTabid())).findAny().isPresent()) {
            it.remove();
          }
        }
        oldErRules.addAll(shallBeAdd);
      }
    }
    this.setBizResult(context, erRule);
  }

  private List<DependencyNode> getDumpNodesFromTopology(String topology) throws Exception {
    SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    return wfTopology.getDumpNodes();
  }

  /**
   * 保存ER关系
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.DATAFLOW_UPDATE)
  public void doSaveErRule(Context context) throws Exception {
    com.alibaba.fastjson.JSONObject j = this.parseJsonPost();
    ERRules erRules = new ERRules();
    final String topology = j.getString("topologyName");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalArgumentException("param 'topology' can not be empty");
    }
    SqlDataFlowTopology df = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    com.alibaba.fastjson.JSONArray edges = j.getJSONArray("edges");
    com.alibaba.fastjson.JSONArray nodes = j.getJSONArray("nodes");
    com.alibaba.fastjson.JSONObject edge = null;
    com.alibaba.fastjson.JSONObject sourceNode = null;
    com.alibaba.fastjson.JSONObject targetNode = null;
    com.alibaba.fastjson.JSONObject linkrule = null;
    com.alibaba.fastjson.JSONArray linkKeyList = null;
    com.alibaba.fastjson.JSONObject link = null;
    com.alibaba.fastjson.JSONObject nodeTuple = null;
    com.alibaba.fastjson.JSONObject node = null;
    com.alibaba.fastjson.JSONObject ermeta = null;
    TableRelation erRelation = null;
    for (int i = 0; i < edges.size(); i++) {
      edge = edges.getJSONObject(i);
      sourceNode = edge.getJSONObject("sourceNode");
      // getTableName(sourceNode);
      targetNode = edge.getJSONObject("targetNode");
      // getTableName(targetNode);
      linkrule = edge.getJSONObject("linkrule");
      if (linkrule == null) {
        throw new IllegalStateException("linkrule can not be null");
      }
      erRelation = $(edge.getString("id"), df, getTableName(targetNode), getTableName(sourceNode), TabCardinality.parse(linkrule.getString("cardinality")));
      linkKeyList = linkrule.getJSONArray("linkKeyList");
      for (int jj = 0; jj < linkKeyList.size(); jj++) {
        link = linkKeyList.getJSONObject(jj);
        erRelation.addJoinerKey(link.getString("parentKey"), link.getString("childKey"));
      }
      erRules.addRelation(erRelation);
    }
    com.alibaba.fastjson.JSONObject nodeMeta = null;
    com.alibaba.fastjson.JSONObject colTransfer = null;
    DependencyNode dumpNode = null;
    com.alibaba.fastjson.JSONArray columnTransferList = null;
    TabExtraMeta tabMeta = null;
    String sharedKey = null;
    for (int index = 0; index < nodes.size(); index++) {
      nodeTuple = nodes.getJSONObject(index);
      node = nodeTuple.getJSONObject("node");
      ermeta = node.getJSONObject("extraMeta");
      dumpNode = new DependencyNode();
      if (ermeta != null) {
        tabMeta = new TabExtraMeta();
        tabMeta.setPrimaryIndexTab(ermeta.getBoolean("primaryIndexTab"));
        if (tabMeta.isPrimaryIndexTab()) {
          sharedKey = ermeta.getString("sharedKey");
          tabMeta.setSharedKey(sharedKey);
          com.alibaba.fastjson.JSONArray primaryIndexColumnNames = ermeta.getJSONArray("primaryIndexColumnNames");
          com.alibaba.fastjson.JSONObject primaryIndex = null;
          List<PrimaryLinkKey> names = Lists.newArrayList();
          PrimaryLinkKey plinkKey = null;
          for (int i = 0; i < primaryIndexColumnNames.size(); i++) {
            primaryIndex = primaryIndexColumnNames.getJSONObject(i);
            plinkKey = new PrimaryLinkKey();
            plinkKey.setName(primaryIndex.getString("name"));
            plinkKey.setPk(primaryIndex.getBoolean("pk"));
            names.add(plinkKey);
          }
          tabMeta.setPrimaryIndexColumnNames(names);
        }
        tabMeta.setMonitorTrigger(ermeta.getBoolean("monitorTrigger"));
        tabMeta.setTimeVerColName(null);
        if (tabMeta.isMonitorTrigger()) {
          tabMeta.setTimeVerColName(ermeta.getString("timeVerColName"));
        }
        columnTransferList = ermeta.getJSONArray("columnTransferList");
        for (int i = 0; i < columnTransferList.size(); i++) {
          colTransfer = columnTransferList.getJSONObject(i);
          tabMeta.addColumnTransfer(new ColumnTransfer(colTransfer.getString("colKey"), colTransfer.getString("transfer"), colTransfer.getString("param")));
        }
        dumpNode.setExtraMeta(tabMeta);
      }
      dumpNode.setId(node.getString("id"));
      nodeMeta = node.getJSONObject("nodeMeta");
      dumpNode.setTabid(nodeMeta.getString("tabid"));
      dumpNode.setDbid(nodeMeta.getString("dbid"));
      Position pos = new Position();
      pos.setX(node.getIntValue("x"));
      pos.setY(node.getIntValue("y"));
      dumpNode.setPosition(pos);
      dumpNode.setName(nodeMeta.getString("tabname"));
      // dumpNode.setExtraSql(nodeMeta.getString("sqlcontent"));
      erRules.addDumpNode(dumpNode);
    }
    List<PrimaryTableMeta> primaryTabs = erRules.getPrimaryTabs();
    if (primaryTabs.size() < 1) {
      this.addErrorMessage(context, "还没有定义ER主索引表");
      return;
    }
    List<PrimaryLinkKey> pkNames = null;
    for (PrimaryTableMeta meta : primaryTabs) {
      if (StringUtils.isEmpty(meta.getSharedKey())) {
        this.addErrorMessage(context, "主索引表:" + meta.getTabName() + " 还未定义分区键");
      }
      pkNames = meta.getPrimaryKeyNames();
      if (pkNames.size() < 1) {
        this.addErrorMessage(context, "主索引表:" + meta.getTabName() + " 还未定义主键");
      }
    }
    if (this.hasErrors(context)) {
      return;
    }
    File parent = new File(SqlTaskNode.parent, topology);
    FileUtils.forceMkdir(parent);
    FileUtils.write(new File(parent, ERRules.ER_RULES_FILE_NAME), ERRules.serialize(erRules), getEncode(), false);
    // System.out.println(j.toJSONString());
    long wfId = df.getProfile().getDataflowId();
    if (wfId < 1) {
      throw new IllegalStateException("topology '" + topology + "' relevant wfid can not be null");
    }
    WorkFlow wf = new WorkFlow();
    wf.setOpTime(new Date());
    WorkFlowCriteria wfCriteria = new WorkFlowCriteria();
    wfCriteria.createCriteria().andIdEqualTo((int) wfId);
    this.getWorkflowDAOFacade().getWorkFlowDAO().updateByExampleSelective(wf, wfCriteria);
  }

  private String getTableName(com.alibaba.fastjson.JSONObject sourceNode) {
    com.alibaba.fastjson.JSONObject nodeMeta = sourceNode.getJSONObject("nodeMeta");
    return nodeMeta.getString("tabname");
  }

  /**
   * 保存 拓扑图
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.DATAFLOW_UPDATE)
  public void doSaveTopology(Context context) throws Exception {
    this.doUpdateTopology(context, (tname, topology) -> {
      final String topologyName = topology.getName();
      WorkFlow workFlow = new WorkFlow();
      workFlow.setName(topologyName);
      IUser user = this.getUser();
      workFlow.setOpUserId(1);
      workFlow.setOpUserName(user.getName());
      workFlow.setGitPath(String.valueOf(topology.getTimestamp()));
      workFlow.setCreateTime(new Date());
      workFlow.setInChange(new Byte("1"));
      topology.getProfile().setDataflowId(this.offlineDAOFacade.getWorkFlowDAO().insert(workFlow));
    });
  }

  private interface TopologyUpdateCallback {

    public void execute(String topologyName, SqlDataFlowTopology topology);
  }

  /**
   * Do get datasource tables. 获取数据库中所有数据源表
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doGetDatasourceTables(Context context) throws Exception {
    this.setBizResult(context, offlineManager.getDatasourceTables());
  }

  /**
   * Do edit workflow. 编辑工作流
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doEditWorkflow(Context context) throws Exception {
    WorkflowPojo pojo = getWorkflowPojo(context);
    if (pojo == null) {
      return;
    }
    offlineManager.editWorkflow(pojo, this, context);
  }

  /**
   * description: 删除一个工作流
   */
  public void doDeleteWorkflow(Context context) throws Exception {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "workflow id 不能为空");
      return;
    }
    offlineManager.deleteWorkflow(id, this, context);
    this.doGetWorkflows(context);
  }

  private WorkflowPojo getWorkflowPojo(Context context) {
    String name = this.getString("workflowName");
    if (StringUtils.isBlank(name)) {
      this.addErrorMessage(context, "工作流名不能为空");
      return null;
    }
    if (!isWordCharacter(name)) {
      this.addErrorMessage(context, "工作流名必须由英文字符，数字和下划线组成");
      return null;
    }
    String taskScript = this.getString("taskScript");
    if (StringUtils.isBlank(taskScript)) {
      this.addErrorMessage(context, "脚本内容不能为空");
      return null;
    }
    return new WorkflowPojo(name, new JoinRule(taskScript));
  }

  /**
   * Do get datasource info. 获得所有数据源库和表
   *
   * @param context the context
   * @throws Exception the exception
   */
  public void doGetDatasourceInfo(Context context) throws Exception {
    // this.setBizResult(context, offlineManager.getDatasourceInfo());
    this.setBizResult(context
      , new ConfigDsMeta(offlineManager.getDatasourceInfo(), TIS.get().getDescriptorList(DataSourceFactory.class)));
  }


  public static class ConfigDsMeta {
    private final Collection<OfflineDatasourceAction.DatasourceDb> dbs;
    private final DescriptorsJSON pluginDesc;

    public ConfigDsMeta(Collection<DatasourceDb> dbs, DescriptorExtensionList<DataSourceFactory, Descriptor<DataSourceFactory>> descList) {
      this.dbs = dbs;
      this.pluginDesc = new DescriptorsJSON(descList);
    }

    public Collection<DatasourceDb> getDbs() {
      return dbs;
    }

    public com.alibaba.fastjson.JSONObject getPluginDesc() {
      return pluginDesc.getDescriptorsJSON();
    }
  }


  /**
   * Do get datasource db. 获取一个db的git配置信息
   *
   * @param context the context
   */
  public void doGetDatasourceDbById(Context context) {
    Integer dbId = this.getInt("id");
    DBConfigSuit configSuit = offlineManager.getDbConfig(this, dbId, DbScope.DETAILED);
//    DBConfig db = configSuit.getDetailed();
//    confusionPassword(db);
//    if ((db = configSuit.getFacade()) != null) {
//      confusionPassword(db);
//    }
    this.setBizResult(context, configSuit);
  }

  private void confusionPassword(DBConfig db) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < db.getPassword().length(); i++) {
      sb.append("*");
    }
    db.setPassword(sb.toString());
  }

  /**
   * Do get datasource table. 获取一个table的git配置信息
   *
   * @param context the context
   */
  public void doGetDatasourceTableById(Context context) throws IOException {
    Integer tableId = this.getInt("id");
    TISTable tableConfig = this.offlineManager.getTableConfig(this, tableId);
    this.setBizResult(context, tableConfig);
  }

  /**
   * 通过sql语句反射sql语句中的列
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doReflectTableCols(Context context) throws Exception {
    String topology = this.getString("topology");
    if (StringUtils.isEmpty(topology)) {
      throw new IllegalArgumentException("param topology can not be null");
    }
    SqlDataFlowTopology wfTopology = SqlTaskNodeMeta.getSqlDataFlowTopology(topology);
    Map<String, DependencyNode> /**
     * id
     */
      dumpNodes = wfTopology.getDumpNodes().stream().collect(Collectors.toMap((d) -> d.getId(), (d) -> d));
    com.alibaba.fastjson.JSONArray sqlAry = this.parseJsonArrayPost();
    com.alibaba.fastjson.JSONObject j = null;
    // String sql = null;
    // List<RowMetaData> rowMetaData = null;
    List<SqlCols> colsMeta = Lists.newArrayList();
    SqlCols sqlCols = null;
    DependencyNode dumpNode = null;
    TISTable tabCfg = null;
    // List<ColumnMetaData> reflectCols = null;
    for (int i = 0; i < sqlAry.size(); i++) {
      j = sqlAry.getJSONObject(i);
      // sql = j.getString("sql");
      sqlCols = new SqlCols();
      sqlCols.setKey(j.getString("key"));
      String dumpNodeId = sqlCols.getKey();
      dumpNode = dumpNodes.get(dumpNodeId);
      if (dumpNode == null) {
        throw new IllegalStateException("key:" + dumpNodeId + " can not find relevant dump node in topplogy '" + topology + "'");
      }

      // tabCfg = GitUtils.$().getTableConfig(dumpNode.getDbName(), dumpNode.getName());
      DataSourceFactoryPluginStore dbPlugin = TIS.getDataBasePluginStore(this, new PostedDSProp(dumpNode.getDbName()));
      TISTable tisTable = dbPlugin.loadTableMeta(dumpNode.getName());
      if (CollectionUtils.isEmpty(tisTable.getReflectCols())) {
        throw new IllegalStateException("db:" + dumpNode.getDbName() + ",table:" + dumpNode.getName() + " relevant table col reflect cols can not be empty");
      }
      sqlCols.setCols(tisTable.getReflectCols());
      colsMeta.add(sqlCols);
    }
    this.setBizResult(context, colsMeta);
  }

  public static class SqlCols {

    private String key;

    List<ColumnMetaData> cols;

    public String getKey() {
      return this.key;
    }

    public void setKey(String key) {
      this.key = key;
    }

    public List<ColumnMetaData> getCols() {
      return this.cols;
    }

    public void setCols(List<ColumnMetaData> cols) {
      this.cols = cols;
    }
  }

  public static final String COMPONENT_START = "component.start";

  public static final String COMPONENT_END = "component.end";

  /**
   * Do execute workflow. 执行数据流任务<br>
   * 仅仅只执行DataFlow的数据构建
   *
   * @param context the context
   * @throws Exception the exception
   */
  @Func(value = PermissionConstant.DATAFLOW_MANAGE)
  public void doExecuteWorkflow(Context context) throws Exception {
    Integer id = this.getInt("id");
    List<PostParam> params = Lists.newArrayList();
    WorkFlow df = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(id);
    Assert.assertNotNull(df);
    params.add(new PostParam(IFullBuildContext.KEY_WORKFLOW_NAME, df.getName()));
    params.add(new PostParam(IFullBuildContext.KEY_WORKFLOW_ID, String.valueOf(id)));
    // TODO 单独触发的DF执行后期要保证该流程最后的执行的结果数据不能用于索引build
    params.add(new PostParam(IFullBuildContext.KEY_APP_SHARD_COUNT, IFullBuildContext.KEY_APP_SHARD_COUNT_SINGLE));
    params.add(new PostParam(COMPONENT_START, FullbuildPhase.FullDump.getName()));
    params.add(new PostParam(COMPONENT_END, FullbuildPhase.JOIN.getName()));
    if (!CoreAction.triggerBuild(this, context, params).success) {
      // throw new IllegalStateException("dataflowid:" + id + " trigger faild");
    }
  }

  /**
   * Do check table logic name repeat. 检查数据库 表逻辑名是否有重复<br/>
   * 并且反射发现表中的列,生成SQL骨架
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT, sideEffect = false)
  public void doCheckTableLogicNameRepeat(Context context) {
    com.alibaba.fastjson.JSONObject form = this.parseJsonPost();
    this.errorsPageShow(context);
    boolean updateMode = !form.getBoolean("isAdd");
    // 物理表
    // TODO 严格来说 table也需要校验
    String table = form.getString("tableName");
    if (StringUtils.isEmpty(table)) {
      this.addErrorMessage(context, "表名不能为空");
      return;
    }

    final String tableLogicName = table;
    if (StringUtils.equals("db_config", tableLogicName)) {
      this.addErrorMessage(context, "表名不能为'db_config'");
      return;
    }
    Integer dbId = form.getIntValue("dbId");
    com.qlangtech.tis.workflow.pojo.DatasourceDb db = this.offlineDAOFacade.getDatasourceDbDAO().selectByPrimaryKey(dbId);
    if (!updateMode && offlineManager.checkTableLogicNameRepeat(tableLogicName, db)) {
      this.addErrorMessage(context, "已经有了相同逻辑名的表");
      return;
    }

    PluginStore<DataSourceFactory> dbPlugin = TIS.getDataBasePluginStore(this, new PostedDSProp(db.getName(), DbScope.DETAILED));

    List<ColumnMetaData> cols = dbPlugin.getPlugin().getTableMetadata(table);// offlineManager.getTableMetadata(db.getName(), table);
    if (cols.size() < 1) {
      this.addErrorMessage(context, "表:[" + table + "]没有定义列");
      return;
    }

    StringBuffer extractSQL = ColumnMetaData.buildExtractSQL(table, cols);

//    StringBuffer sql = new StringBuffer();
//    sql.append("SELECT ");
//    sql.append(Joiner.on(",").join(cols.stream().map((r) -> r.getKey()).iterator())).append("\n");
//    sql.append("FROM ").append(table);
//    if (!StringUtils.equals(table, tableLogicName)) {
//      sql.append(" AS ").append(tableLogicName);
//    }
    TableReflect tableReflect = new TableReflect();
    tableReflect.setCols(cols);
    tableReflect.setSql(extractSQL.toString());
    this.setBizResult(context, tableReflect);
  }

  /**
   * 日常db同步到线上
   *
   * @param context
   */
  public void doSyncDb(Context context) {
    Integer dbId = this.getInt("id");
    if (dbId == null) {
      this.addErrorMessage(context, "dbId不能为空");
      return;
    }
    String dbName = this.getString("dbName");
    // this.offlineManager.syncDb(dbId, dbName, this, context);
  }

  /**
   * 日常table同步到线上
   *
   * @param context
   */
  public void doSyncTable(Context context) {
    Integer tableId = this.getInt("id");
    if (tableId == null) {
      this.addErrorMessage(context, "table id 不能为空");
      return;
    }
    String tableLogicName = this.getString("tableLogicName");
    // this.offlineManager.syncTable(tableId, tableLogicName, this,
    // context);
  }

  /**
   * 线上控制台使用，用来添加db记录
   *
   * @param context
   */
  public void doSyncDbRecord(Context context) {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "id不能为空");
      this.setBizResult(context, false);
      return;
    }
    String name = this.getString("name");
    com.qlangtech.tis.workflow.pojo.DatasourceDb datasourceDb = new com.qlangtech.tis.workflow.pojo.DatasourceDb();
    datasourceDb.setId(id);
    datasourceDb.setName(name);
    datasourceDb.setSyncOnline(new Byte("0"));
    Date now = new Date();
    datasourceDb.setCreateTime(now);
    this.offlineManager.syncDbRecord(datasourceDb, this, context);
  }

  /**
   * 线上控制台使用，用来添加table记录
   *
   * @param context
   */
  public void doSyncTableRecord(Context context) {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "id不能为空");
      this.setBizResult(context, false);
      return;
    }
    String name = this.getString("name");
    String tableLogicName = this.getString("tableLogicName");
    Integer dbId = this.getInt("db_id");
    if (dbId == null) {
      this.addErrorMessage(context, "db_id不能为空");
      this.setBizResult(context, false);
      return;
    }
    String gitTag = this.getString("git_tag");
    DatasourceTable datasourceTable = new DatasourceTable();
    datasourceTable.setId(id);
    datasourceTable.setName(name);
    datasourceTable.setTableLogicName(tableLogicName);
    datasourceTable.setDbId(dbId);
    datasourceTable.setGitTag(gitTag);
    Date now = new Date();
    datasourceTable.setCreateTime(now);
    this.offlineManager.syncTableRecord(datasourceTable, this, context);
  }

  /**
   * 删除db
   *
   * @param context
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doDeleteDatasourceDbById(Context context) {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "db id不能为空");
      return;
    }
    this.offlineManager.deleteDatasourceDbById(id, this, context);
  }

  /**
   * 删除table
   *
   * @param context
   */
  @Func(value = PermissionConstant.PERMISSION_DATASOURCE_EDIT)
  public void doDeleteDatasourceTableById(Context context) {
    Integer id = this.getInt("id");
    this.offlineManager.deleteDatasourceTableById(id, this, context);
  }

  /**
   * 删除变更
   *
   * @param context
   */
  public void doDeleteWorkflowChange(Context context) throws Exception {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "workflow id不能为空");
      return;
    }
    this.offlineManager.deleteWorkflowChange(id, this, context);
    // this.doGetWorkflowChanges(context);
  }

  /**
   * 确认变更 同步到线上
   *
   * @param context
   */
  public void doConfirmWorkflowChange(Context context) throws Exception {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "workflow id不能为空");
      return;
    }
    this.offlineManager.confirmWorkflowChange(id, this, context);
    // this.doGetWorkflows(context);
  }

  /**
   * 获取工作流变更管理列表
   *
   * @param context
   * @throws Exception
   */
  public void doGetWorkflowChanges(Context context) {
    Integer page = this.getInt("page");
    if (page == null) {
      page = 1;
    }
    Integer workflowId = this.getInt("workflowId");
    if (workflowId == null) {
      this.setBizResult(context, this.offlineManager.getWorkflowChanges(page));
    } else {
      this.setBizResult(context, this.offlineManager.getWorkflowChanges(page, workflowId));
    }
  }

  /**
   * 创建一个工作流变更
   *
   * @param context
   */
  public void doCreateWorkflowChange(Context context) {
    String changeReason = this.getString("changeReason");
    if (StringUtils.isBlank(changeReason)) {
      this.addErrorMessage(context, "请输入变更理由");
      return;
    }
    String workflowName = this.getString("workflowName");
    if (StringUtils.isBlank(workflowName)) {
      this.addErrorMessage(context, "请选择工作流");
      return;
    }
    this.offlineManager.createWorkflowChange(changeReason, workflowName, this, context);
  }

  /**
   * 获取一个工作流的配置
   *
   * @param context
   */
  public void doGetWorkflowConfig(Context context) {
    Integer id = this.getInt("id");
    if (id == null) {
      this.addErrorMessage(context, "请输入工作流id");
      return;
    }
    this.setBizResult(context, this.offlineManager.getWorkflowConfig(id, true));
  }

  // /**
  // * 获取一个工作流的配置
  // *
  // * @param context
  // */
  // public void doGetWorkflowConfigBranch(Context context) {
  // Integer id = this.getInt("id");
  // if (id == null) {
  // this.addErrorMessage(context, "请输入工作流id");
  // return;
  // }
  // this.setBizResult(context, this.offlineManager.getWorkflowConfig(id, false));
  // }
  // /**
  // * 获取某个
  // *
  // * @param context
  // */
  // public void doGetWorkflowConfigSha1(Context context) {
  // String name = this.getString("name");
  // if (StringUtils.isBlank(name)) {
  // this.addErrorMessage(context, "工作流名字不能为空");
  // return;
  // }
  // String gitSha1 = this.getString("gitSha1");
  // if (StringUtils.isBlank(gitSha1)) {
  // this.addErrorMessage(context, "请输入正确的commit id");
  // return;
  // }
  // this.setBizResult(context, this.offlineManager.getWorkflowConfig(name,
  // gitSha1));
  // }
  // public void doUseWorkflowChange(Context context) {
  // Integer id = this.getInt("id");
  // if (id == null) {
  // this.addErrorMessage(context, "请传入变更id");
  // return;
  // }
  // this.offlineManager.useWorkflowChange(id, this, context);
  // }
  // public void doCompareWorkflowChanges(Context context) {
  // String path = this.getString("path");
  // String fromVersion = this.getString("fromVersion");
  // String toVersion = this.getString("toVersion");
  // String fromString =
  // GitUtils.$().getWorkflowSha(GitUtils.WORKFLOW_GIT_PROJECT_ID, fromVersion,
  // path).getTask();
  // String toString =
  // GitUtils.$().getWorkflowSha(GitUtils.WORKFLOW_GIT_PROJECT_ID, toVersion,
  // path).getTask();
  // this.setBizResult(context, getTwoStringDiffHtml(fromString, toString));
  // }
  // private static String getTwoStringDiffHtml(String s1, String s2) {
  // StringBuilder sb = new StringBuilder();
  // LinkedList<diff_match_patch.Diff> differ = DIFF_MATCH_PATCH.diff_main(s1, s2,
  // true);
  //
  // for (diff_match_patch.Diff d : differ) {
  //
  // if (d.operation == diff_match_patch.Operation.EQUAL) {
  // sb.append(StringEscapeUtils.escapeXml(d.text));
  // } else if (d.operation == diff_match_patch.Operation.DELETE) {
  // sb.append("<span
  // style='text-decoration:line-through;background-color:pink;'>")
  // .append(StringEscapeUtils.escapeXml(d.text)).append("</span>");
  // } else if (d.operation == diff_match_patch.Operation.INSERT) {
  // sb.append("<span
  // style=\"background-color:#00FF00;\">").append(StringEscapeUtils.escapeXml(d.text))
  // .append("</span>");
  // }
  //
  // }
  // return sb.toString();
  // }
  @Autowired
  public void setWfDaoFacade(IComDfireTisWorkflowDAOFacade facade) {
    this.offlineDAOFacade = facade;
  }

  public static class DatasourceDb {

    int id;

    String name;

    List<DatasourceTable> tables;

    byte syncOnline;

    public DatasourceDb() {
    }

    public int getId() {
      return id;
    }

    public void setId(int id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public List<DatasourceTable> getTables() {
      return tables;
    }

    public void setTables(List<DatasourceTable> tables) {
      this.tables = tables;
    }

    public void addTable(DatasourceTable datasourceTable) {
      if (this.tables == null) {
        this.tables = new LinkedList<>();
      }
      this.tables.add(datasourceTable);
    }

    public byte getSyncOnline() {
      return syncOnline;
    }

    public void setSyncOnline(byte syncOnline) {
      this.syncOnline = syncOnline;
    }
  }

  public static String getHiveType(int type) {
    switch (type) {
      case BIT:
      case TINYINT:
      case SMALLINT:
      case INTEGER:
        return "INT";
      case BIGINT:
        return "BIGINT";
      case FLOAT:
      case REAL:
      case DOUBLE:
      case NUMERIC:
      case DECIMAL:
        return "DOUBLE";
      default:
        return "STRING";
    }
  }

  public static String getDbType(int type) {
    switch (type) {
      case BIT:
        return "BIT";
      case TINYINT:
        return "TINYINT";
      case SMALLINT:
        return "SMALLINT";
      case INTEGER:
        return "INTEGER";
      case BIGINT:
        return "BIGINT";
      case FLOAT:
        return "FLOAT";
      case REAL:
        return "REAL";
      case DOUBLE:
        return "DOUBLE";
      case NUMERIC:
        return "NUMERIC";
      case DECIMAL:
        return "DECIMAL";
      default:
        return "VARCHAR";
    }
  }
}
