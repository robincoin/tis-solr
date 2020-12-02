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
package com.qlangtech.tis.coredefine.module.action;

import com.alibaba.citrus.turbine.Context;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.koubei.web.tag.pager.Pager;
import com.qlangtech.tis.TIS;
import com.qlangtech.tis.TisZkClient;
import com.qlangtech.tis.cloud.ICoreAdminAction;
import com.qlangtech.tis.common.utils.Assert;
import com.qlangtech.tis.compiler.streamcode.GenerateDAOAndIncrScript;
import com.qlangtech.tis.compiler.streamcode.IndexStreamCodeGenerator;
import com.qlangtech.tis.coredefine.biz.FCoreRequest;
import com.qlangtech.tis.coredefine.module.control.SelectableServer;
import com.qlangtech.tis.coredefine.module.control.SelectableServer.CoreNode;
import com.qlangtech.tis.coredefine.module.screen.Corenodemanage.InstanceDirDesc;
import com.qlangtech.tis.coredefine.module.screen.Corenodemanage.ReplicState;
import com.qlangtech.tis.exec.IIndexMetaData;
import com.qlangtech.tis.fullbuild.IFullBuildContext;
import com.qlangtech.tis.manage.PermissionConstant;
import com.qlangtech.tis.manage.biz.dal.dao.IClusterSnapshotDAO;
import com.qlangtech.tis.manage.biz.dal.pojo.*;
import com.qlangtech.tis.manage.common.*;
import com.qlangtech.tis.manage.common.ConfigFileContext.StreamProcess;
import com.qlangtech.tis.manage.common.HttpUtils.PostParam;
import com.qlangtech.tis.manage.servlet.QueryCloudSolrClient;
import com.qlangtech.tis.manage.servlet.QueryIndexServlet;
import com.qlangtech.tis.manage.servlet.QueryResutStrategy;
import com.qlangtech.tis.manage.spring.aop.Func;
import com.qlangtech.tis.order.center.IParamContext;
import com.qlangtech.tis.plugin.PluginStore;
import com.qlangtech.tis.plugin.incr.IncrStreamFactory;
import com.qlangtech.tis.pubhook.common.RunEnvironment;
import com.qlangtech.tis.realtime.yarn.rpc.IndexJobRunningStatus;
import com.qlangtech.tis.realtime.yarn.rpc.JobType;
import com.qlangtech.tis.runtime.module.action.BasicModule;
import com.qlangtech.tis.runtime.module.action.ClusterStateCollectAction;
import com.qlangtech.tis.runtime.module.screen.BasicScreen;
import com.qlangtech.tis.runtime.module.screen.ViewPojo;
import com.qlangtech.tis.runtime.pojo.ServerGroupAdapter;
import com.qlangtech.tis.solrdao.SolrFieldsParser;
import com.qlangtech.tis.solrj.util.ZkUtils;
import com.qlangtech.tis.sql.parser.DBNode;
import com.qlangtech.tis.sql.parser.stream.generate.FacadeContext;
import com.qlangtech.tis.sql.parser.stream.generate.StreamCodeContext;
import com.qlangtech.tis.workflow.dao.IWorkFlowBuildHistoryDAO;
import com.qlangtech.tis.workflow.pojo.*;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.common.cloud.*;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.zookeeper.data.Stat;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.solr.common.cloud.ZkStateReader.BASE_URL_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;

/**
 * core 应用定义
 *
 * @author 百岁（baisui@qlangtech.com）
 * @date 2016年8月4日
 */
public class CoreAction extends BasicModule {

  public static final String DEFAULT_SOLR_CONFIG = "tis_mock_config";

  private static final long serialVersionUID = -6753169329484480543L;

  private static final Pattern PATTERN_IP = Pattern.compile("^((\\d+?).(\\d+?).(\\d+?).(\\d+?)):(\\d+)_solr$");

  // private final String CLIENT_ZK_PATH = "/terminator/dump-controller/";
  public static final XMLResponseParser RESPONSE_PARSER = new XMLResponseParser();

  private static final int MAX_SHARDS_PER_NODE = 16;

  private static final Logger log = LoggerFactory.getLogger(CoreAction.class);

  private static final Pattern placehold_pattern = Pattern.compile("\\$\\{(.+)\\}");

  public static final String CREATE_CORE_SELECT_COREINFO = "selectCoreinfo";

  private IClusterSnapshotDAO clusterSnapshotDAO;

  /**
   * 重新启动增量执行进程
   *
   * @param context
   * @throws Exception
   */
  public void doRelaunchIncrProcess(Context context) throws Exception {
    PluginStore<IncrStreamFactory> incrStreamStore = getIncrStreamFactoryStore(true);
    IncrStreamFactory incrStream = incrStreamStore.getPlugin();
    IIncrSync incrSync = incrStream.getIncrSync();
    incrSync.relaunch(this.getCollectionName());
  }

  /**
   * 取得当前增量的配置状态
   *
   * @param context
   */
  public void doGetIncrStatus(Context context) throws Exception {
    IndexIncrStatus incrStatus = new IndexIncrStatus();
    // 是否可以取缓存中的deployment信息，在刚删除pod重启之后需要取全新的deployment信息不能缓存
    final boolean cache = this.getBoolean("cache");
    PluginStore<IncrStreamFactory> store = getIncrStreamFactoryStore();
    if (store.getPlugin() == null) {
      incrStatus.setK8sPluginInitialized(false);
      this.setBizResult(context, incrStatus);
      return;
    }
    incrStatus.setK8sPluginInitialized(true);
    IndexStreamCodeGenerator indexStreamCodeGenerator = this.getIndexStreamCodeGenerator();
    StreamCodeContext streamCodeContext = new StreamCodeContext(this.getCollectionName(), indexStreamCodeGenerator.incrScriptTimestamp);
    incrStatus.setIncrScriptCreated(streamCodeContext.isIncrScriptDirCreated());
    TISK8sDelegate k8s = TISK8sDelegate.getK8SDelegate(this.getCollectionName());
    IncrDeployment rcConfig = k8s.getRcConfig(cache);
    incrStatus.setK8sReplicationControllerCreated(rcConfig != null);
    if (rcConfig != null) {
      incrStatus.setIncrDeployment(rcConfig);
      JobType.RemoteCallResult<IndexJobRunningStatus> callResult
        = JobType.QueryIndexJobRunningStatus.assembIncrControlWithResult(
        this.getCollectionName(), Collections.emptyList(), IndexJobRunningStatus.class);
      if (callResult.success) {
        incrStatus.setIncrProcess(callResult.biz);
      }
    }
    this.setBizResult(context, incrStatus);
  }

  private PluginStore<IncrStreamFactory> getIncrStreamFactoryStore() {
    return getIncrStreamFactoryStore(false);
  }

  private PluginStore<IncrStreamFactory> getIncrStreamFactoryStore(boolean validateNull) {
    PluginStore<IncrStreamFactory> store = TIS.getPluginStore(this, this.getCollectionName(), IncrStreamFactory.class);
    if (validateNull && store.getPlugin() == null) {
      throw new IllegalStateException("collection:" + this.getCollectionName() + " relevant IncrStreamFactory store can not be null");
    }
    return store;
  }

  /**
   * 创建增量通道 <br>
   * 1. 生成dao层代码，存在本地 <br>
   * 2. 对生成的代码进行打包 <br>
   * 3. 将代码包发布到仓库中备用，并对本次代码包进行标记 <br>
   * 4. 将标记记录到数据库中 <br>ƒ
   * 5. 自动生成增量代码（scala版本的） <br>
   * 6. 对生成的代码包进行编译，打包 7. 将生成的代码包发布到仓库中备用，并对本次代码包进行标记 <br>
   * 8. 将标记记录到数据库中 <br>
   * 9. 结合dao层代码包和scala层代码包，再结合之前已经构建完成的docker镜像包，将增量代码发布到k8s集群中去<br>
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_CONFIG_EDIT, sideEffect = false)
  public void doCreateIncrSyncChannal(Context context) throws Exception {
    IndexStreamCodeGenerator indexStreamCodeGenerator = this.getIndexStreamCodeGenerator();
    List<FacadeContext> facadeList = indexStreamCodeGenerator.getFacadeList();
    PluginStore<IncrStreamFactory> store = TIS.getPluginStore(IncrStreamFactory.class);
    IndexIncrStatus incrStatus = new IndexIncrStatus();
    if (store.getPlugin() != null) {
      // 已经定义了全局插件
      PluginStore<IncrStreamFactory> collectionBindIncrStreamFactoryStore = getIncrStreamFactoryStore();
      if (collectionBindIncrStreamFactoryStore.getPlugin() == null) {
        // 需要将全局插件属性拷贝到collection绑定的插件属性上来
        collectionBindIncrStreamFactoryStore.copyFrom(store);
      }
    }
    // 这里永远是false应该
    incrStatus.setK8sPluginInitialized(false);
    // 判断是否已经成功创建，如果已经创建了，就退出
    if (indexStreamCodeGenerator.isIncrScriptDirCreated()) {
      incrStatus.setIncrScriptCreated(true);
      incrStatus.setIncrScriptMainFileContent(indexStreamCodeGenerator.readIncrScriptMainFileContent());
      log.info("incr script has create ignore it file path:{}", indexStreamCodeGenerator.getIncrScriptDirPath());
      // this.setBizResult(context, incrStatus);
    }
    GenerateDAOAndIncrScript generateDAOAndIncrScript = new GenerateDAOAndIncrScript(this, indexStreamCodeGenerator);
    generateDAOAndIncrScript.generate(context, incrStatus, false, this.getDependencyDbsMap(indexStreamCodeGenerator));
    this.setBizResult(context, incrStatus);
  }

  /**
   * 编译打包
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_CONFIG_EDIT)
  public void doCompileAndPackage(Context context) throws Exception {
    IndexStreamCodeGenerator indexStreamCodeGenerator = getIndexStreamCodeGenerator();
    IndexIncrStatus incrStatus = new IndexIncrStatus();
    GenerateDAOAndIncrScript daoAndIncrScript = new GenerateDAOAndIncrScript(this, indexStreamCodeGenerator);
    Map<Integer, Long> dependencyDbs = getDependencyDbsMap(indexStreamCodeGenerator);
    daoAndIncrScript.generate(context, incrStatus, true, dependencyDbs);
  }

  /**
   * 部署实例
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_MANAGE)
  public void doDeployIncrSyncChannal(Context context) throws Exception {
    IncrUtils.IncrSpecResult applySpec = IncrUtils.parseIncrSpec(context, this.parseJsonPost(), this);
    if (!applySpec.isSuccess()) {
      return;
    }
    // 编译并且打包
    IndexStreamCodeGenerator indexStreamCodeGenerator = getIndexStreamCodeGenerator();
    IndexIncrStatus incrStatus = new IndexIncrStatus();
    // 将打包好的构建，发布到k8s集群中去
    // https://github.com/kubernetes-client/java
    TISK8sDelegate k8sClient = TISK8sDelegate.getK8SDelegate(this.getCollectionName());
    // 通过k8s发布
    k8sClient.deploy(applySpec.getSpec(), indexStreamCodeGenerator.getIncrScriptTimestamp());
    this.setBizResult(context, incrStatus);
  }

  private Map<Integer, Long> getDependencyDbsMap(IndexStreamCodeGenerator indexStreamCodeGenerator) {
    final Map<DBNode, List<String>> dbNameMap = indexStreamCodeGenerator.getDbTables();
    if (dbNameMap.size() < 1) {
      throw new IllegalStateException("dbNameMap size can not small than 1");
    }
    // Map<Integer /**DBID*/, Long /**timestamp ver*/> dependencyDbs = null;
    DatasourceDbCriteria dbCriteria = new DatasourceDbCriteria();
    dbCriteria.createCriteria().andIdIn(dbNameMap.keySet().stream().map((dbNode -> dbNode.getDbId())).collect(Collectors.toList()));
    return this.getWorkflowDAOFacade().getDatasourceDbDAO().selectByExample(dbCriteria).stream().collect(Collectors.toMap(DatasourceDb::getId, (r) -> ManageUtils.formatNowYyyyMMddHHmmss(r.getOpTime())));
  }

  private IndexStreamCodeGenerator getIndexStreamCodeGenerator() throws Exception {
    Integer workflowid = this.getAppDomain().getApp().getWorkFlowId();
    WorkFlow workFlow = CoreAction.this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(workflowid);
    return new IndexStreamCodeGenerator(this.getCollectionName(), workFlow.getName(), ManageUtils.formatNowYyyyMMddHHmmss(workFlow.getOpTime()), (dbid, rewriteableTables) -> {
      // 通过dbid返回db中的所有表名称
      DatasourceTableCriteria tableCriteria = new DatasourceTableCriteria();
      tableCriteria.createCriteria().andDbIdEqualTo(dbid);
      List<DatasourceTable> tableList = CoreAction.this.getWorkflowDAOFacade().getDatasourceTableDAO().selectByExample(tableCriteria);
      return tableList.stream().map((t) -> t.getName()).collect(Collectors.toList());
    });
  }

  // /**
  // *
  // * @param incrStatus
  // * @param compilerAndPackage
  // * @throws Exception
  // */
  // private void generateDAOAndIncrScript(
  // Context context,
  // IndexStreamCodeGenerator indexStreamCodeGenerator,
  // IndexIncrStatus incrStatus, boolean compilerAndPackage) throws Exception {
  // final Map<DBNode, List<String>> dbNameMap = indexStreamCodeGenerator.getDbTables();
  // if (dbNameMap.size() < 1) {
  // throw new IllegalStateException("dbNameMap size can not small than 1");
  // }
  // DBConfig dbConfig = null;
  // DatasourceDbCriteria dbCriteria = new DatasourceDbCriteria();
  // dbCriteria.createCriteria().andIdIn(dbNameMap.keySet().stream().map((dbNode -> dbNode.getDbId())).collect(Collectors.toList()));
  //
  // Map<Integer /**DBID*/, Date> dependencyDbs = this.getWorkflowDAOFacade().getDatasourceDbDAO()
  // .selectByExample(dbCriteria).stream().collect(Collectors.toMap(DatasourceDb::getId, DatasourceDb::getOpTime));
  // if (dbNameMap.size() != dependencyDbs.size()) {
  // throw new IllegalStateException("dbNameMap.size() " + dbNameMap.size() + " != dependencyDbs.size()" + dependencyDbs.size());
  // }
  //
  // //long timestampp;// = Long.parseLong(ManageUtils.formatNowYyyyMMddHHmmss());
  // final KoubeiProgressCallback koubeiProgressCallback = new KoubeiProgressCallback();
  //
  // List<IbatorContext> daoFacadeList = Lists.newArrayList();
  // Date lastOptime = null;
  // for (Map.Entry<DBNode /* dbname */, List<String>> entry : dbNameMap.entrySet()) {
  //
  // lastOptime = dependencyDbs.get(entry.getKey().getDbId());
  // if (lastOptime == null) {
  // throw new IllegalStateException("db " + entry.getKey()
  // + " is not find in dependency dbs:"
  // + dbNameMap.keySet().stream().map((r) -> "[" + r.getDbId() + ":" + r.getDbName() + "]")
  // .collect(Collectors.joining(",")));
  // }
  // long timestamp = ManageUtils.formatNowYyyyMMddHHmmss(lastOptime);
  // dbConfig = GitUtils.$().getDbLinkMetaData( //
  // entry.getKey().getDbName(), RunEnvironment.getSysRuntime(), DbScope.DETAILED);
  //
  // IbatorProperties properties = new IbatorProperties(dbConfig, entry.getValue(), timestamp);
  //
  // //entry.getKey().setDaoDir(properties.getDaoDir());
  // entry.getKey().setTimestampVer(timestamp);
  //
  // KoubeiIbatorRunner runner = new KoubeiIbatorRunner(properties) {
  // @Override
  // protected KoubeiProgressCallback getProgressCallback() {
  // return koubeiProgressCallback;
  // }
  // };
  // IbatorContext ibatorContext = runner.getIbatorContext();
  // daoFacadeList.add(ibatorContext);
  //
  //
  // if (entry.getValue().size() < 1) {
  // throw new IllegalStateException("db:" + entry.getKey() + " relevant tablesList can not small than 1");
  // }
  //
  // try {
  //
  // if (!properties.isDaoScriptCreated()) {
  // // 生成源代码
  // runner.build();
  // // dao script 脚本已经创建不需要再创建了
  // continue;
  // }
  // // 类型名称
  // if (compilerAndPackage) {
  // File classpathDir = new File("/Users/mozhenghua/Desktop/j2ee_solution/project/tis-ibatis/target/dependency");
  //
  // JavaCompilerProcess daoCompilerPackageProcess
  // = new JavaCompilerProcess(dbConfig, properties.getDaoDir(), classpathDir);
  // // 打包,生成jar包
  // daoCompilerPackageProcess.compileAndBuildJar();
  // }
  //
  // } catch (Exception e) {
  // // 将文件夹清空
  // FileUtils.forceDelete(properties.getDaoDir());
  // throw new RuntimeException("dao path:" + properties.getDaoDir(), e);
  // }
  // }
  //
  // daoFacadeList.stream().forEach((r) -> {
  // FacadeContext fc = new FacadeContext();
  // fc.setFacadeInstanceName(r.getFacadeInstanceName());
  // fc.setFullFacadeClassName(r.getFacadeFullClassName());
  // fc.setFacadeInterfaceName(r.getFacadeInterface());
  // indexStreamCodeGenerator.facadeList.add(fc);
  // });
  //
  // /************************************************************************************
  // * 自动生成scala代码,及spring相关配置文件
  // *************************************************************************************/
  // try {
  // if (!indexStreamCodeGenerator.isIncrScriptDirCreated()) {
  // indexStreamCodeGenerator.generateStreamScriptCode();
  // // 生成依赖dao依赖元数据信息
  // DBNode.dump(dbNameMap.keySet().stream().collect(Collectors.toList())
  // , StreamContextConstant.getDbDependencyConfigMetaFile(
  // indexStreamCodeGenerator.getAppDomain().getAppName(), indexStreamCodeGenerator.incrScriptTimestamp));
  //
  // // 生成Spring XML配置文件
  // indexStreamCodeGenerator.generateConfigFiles();
  // }
  //
  // incrStatus.setIncrScriptMainFileContent(indexStreamCodeGenerator.readIncrScriptMainFileContent());
  //
  // // scala 代码编译
  // //https://github.com/davidB/scala-maven-plugin/blob/master/src/main/java/scala_maven_executions/JavaMainCallerSupport.java
  // //TODO 真实生产环境中需要 和 代码build阶段分成两步
  // if (compilerAndPackage) {
  //
  // File sourceRoot = StreamContextConstant.getStreamScriptRootDir(
  // indexStreamCodeGenerator.getAppDomain().getAppName(), indexStreamCodeGenerator.incrScriptTimestamp);//  new File("/opt/data/streamscript/" + appDomain.getAppName() + File.separator + timestamp);
  //
  // // 编译Scala代码
  //
  // if (this.streamScriptCompile(sourceRoot, dbNameMap.keySet())) {
  // this.addErrorMessage(context, "增量脚本编译失败");
  // return;
  // }
  //
  // // 对scala代码进行 打包
  // JavaCompilerProcess.SourceGetterStrategy getterStrategy  //
  // = new JavaCompilerProcess.SourceGetterStrategy(false, "/src/main/scala", ".scala") {
  // @Override
  // public JavaFileObject.Kind getSourceKind() {
  // // 没有scala的类型，暂且用other替换一下
  // return JavaFileObject.Kind.OTHER;
  // }
  //
  // @Override
  // public MyJavaFileObject processMyJavaFileObject(MyJavaFileObject fileObj) {
  // try {
  // try (InputStream input = FileUtils.openInputStream(fileObj.getSourceFile())) {
  // IOUtils.copy(input, fileObj.openOutputStream());
  // }
  // } catch (IOException e) {
  // throw new RuntimeException(e);
  // }
  //
  // return fileObj;
  // }
  // };
  //
  // //
  // JavaCompilerProcess.FileObjectsContext fileObjects = JavaCompilerProcess.getFileObjects(sourceRoot, getterStrategy);
  //
  // final JavaCompilerProcess.FileObjectsContext compiledCodeContext = new JavaCompilerProcess.FileObjectsContext();
  //
  // File streamScriptClassesDir = new File(sourceRoot, "classes");
  // (streamScriptClassesDir, compiledCodeContext, null);
  //
  // // 取得spring配置文件相关resourece
  // JavaCompilerProcess.FileObjectsContext xmlConfigs =
  // indexStreamCodeGenerator.getSpringXmlConfigsObjectsContext();
  //
  // // 将stream code打包
  // JavaCompilerProcess.packageJar(sourceRoot
  // , StreamContextConstant.getIncrStreamJarName(indexStreamCodeGenerator.getAppDomain().getAppName())  //indexStreamCodeGenerator.getAppDomain().getAppName() + "-incr.jar"
  // , fileObjects, compiledCodeContext, xmlConfigs);
  // }
  //
  // } catch (Exception e) {
  // // 将原始文件删除干净
  // try {
  // FileUtils.forceDelete(indexStreamCodeGenerator.getStreamCodeGenerator().getIncrScriptDir());
  // } catch (Throwable ex) {
  // // ex.printStackTrace();
  // }
  // throw new RuntimeException(e);
  // }
  // }
  // private static void appendDBDependenciesClasspath(Set<String> classpathElements, Set<DBNode> dependencyDBNodes) {
  //
  // for (DBNode db : dependencyDBNodes) {
  // File jarFile = new File(db.getDaoDir(), db.getDbName() + "-dao.jar");
  // if (!jarFile.exists()) {
  // throw new IllegalStateException("jarfile:" + jarFile.getAbsolutePath() + " is not exist");
  // }
  // classpathElements.add(jarFile.getAbsolutePath());
  // }
  // }
  // private void apappendClassFilependClassFile(
  // File parent, JavaCompilerProcess.FileObjectsContext fileObjects
  // , final StringBuffer qualifiedClassName) throws IOException {
  //
  // String[] children = parent.list();
  // File childFile = null;
  // for (String child : children) {
  // childFile = new File(parent, child);
  // if (childFile.isDirectory()) {
  // StringBuffer newQualifiedClassName = null;
  // if (qualifiedClassName == null) {
  // newQualifiedClassName = new StringBuffer(child);
  // } else {
  // newQualifiedClassName = (new StringBuffer(qualifiedClassName)).append(".").append(child);
  // }
  // appendClassFile(childFile, fileObjects, newQualifiedClassName);
  // } else {
  //
  // final String className = StringUtils.substringBeforeLast(child, ".");
  // NestClassFileObject fileObj = MyJavaFileManager.getNestClassFileObject( //
  // ((new StringBuffer(qualifiedClassName)).append(".").append(className)).toString(), fileObjects.classMap);
  //
  // try (InputStream input = FileUtils.openInputStream(childFile)) {
  // IOUtils.copy(input, fileObj.openOutputStream());
  // }
  // }
  // }
  // }
  //
  // /**
  // * 校验表单中
  // *
  // * @param context
  // */
  // @SuppressWarnings("all")
  // public void doValidatePluginField(Context context) throws Exception {
  //
  // com.alibaba.fastjson.JSONObject form = this.getFormJSON();
  //
  // String keyname = this.getString("keyname");
  // String pluginClass = form.getString("klass");
  // // String val = this.getString("val");
  //
  // Descriptor<?> desc = Jenkins.getInstance().getDescriptor(pluginClass);
  // CheckMethod check = desc.getCheckMethod(keyname);
  // if (StringUtils.isEmpty(check.getDependsOn())) {
  // // 说明check方法不存在
  // return;
  // }
  //
  // List<String> paramNames = ((List<String>) paramsNameField.get(check));
  // List<String> paramVals = new ArrayList<>();
  //
  // for (String n : paramNames) {
  // paramVals.add(form.getString(n));
  // }
  //
  // FormValidation validateResult = (FormValidation) ((java.lang.reflect.Method)
  // fieldCheckField.get(check))
  // .invoke(desc, paramVals.toArray(new String[paramVals.size()]));
  //
  // // 直接就把校验结果打到客户端去了
  // this.setBizResult(context, new FormValidationResult(validateResult));
  // }
  //
  // public static class FormValidationResult {
  // private final Kind kind;
  // private final String message;
  //
  // FormValidationResult(FormValidation validateResult) {
  // this.kind = validateResult.kind;
  // this.message = validateResult.getMessage();
  // }
  //
  // public Kind getKind() {
  // return kind;
  // }
  //
  // public String getMessage() {
  // return message;
  // }
  // }
  // private com.alibaba.fastjson.JSONObject getFormJSON() throws Exception {
  // String postContent = IOUtils.toString(this.getRequest().getInputStream(),
  // getEncode());
  // if (StringUtils.isBlank(postContent)) {
  // throw new IllegalStateException("param postContent can not be blank");
  // }
  // return JSON.parseObject(postContent);
  // }
  // private com.alibaba.fastjson.JSONArray getFormJSONArray() throws Exception {
  // String postContent = IOUtils.toString(this.getRequest().getInputStream(),
  // getEncode());
  // if (StringUtils.isBlank(postContent)) {
  // throw new IllegalStateException("param postContent can not be blank");
  // }
  // return JSON.parseArray(postContent);
  // }
  // private static final Comparator<FormFieldDesc> formFieldDescComparator = new Comparator<FormFieldDesc>() {
  // @Override
  // public int compare(FormFieldDesc o1, FormFieldDesc o2) {
  // return o1.order - o2.order;
  // }
  // };
  public static void main(String[] args) {
    // List<FormFieldDesc> fieldsDesc = Lists.newArrayList();
    // FormFieldDesc desc = new FormFieldDesc();
    // desc.name = "aa";
    // desc.order = 1;
    // fieldsDesc.add(desc);
    //
    // desc = new FormFieldDesc();
    // desc.name = "bb";
    // desc.order = 2;
    //
    // fieldsDesc.add(desc);
    //
    // Collections.sort(fieldsDesc, formFieldDescComparator);
    //
    // for (FormFieldDesc d : fieldsDesc) {
    // System.out.println(d.getName());
    // }
  }

  // public static class TISPluginDescriptor<T extends Describable<T>> {
  //
  // @JSONField(serialize = false)
  // private final Descriptor<T> desc;
  //
  // public TISPluginDescriptor(Descriptor<T> desc) {
  // super();
  // this.desc = desc;
  // }
  //
  // public static <T extends Describable<T>> List<TISPluginDescriptor<T>>
  // convert(List<Descriptor<T>> descList) {
  // List<TISPluginDescriptor<T>> resultList = Lists.newArrayList();
  // for (Descriptor<T> d : descList) {
  // resultList.add(new TISPluginDescriptor<>(d));
  // }
  // return resultList;
  // }
  //
  // public String getDisplayName() {
  // return desc.getDisplayName();
  // }
  //
  // public String getId() {
  // return desc.getId();
  // }
  //
  // public List<FormFieldDesc> getFormFieldsDesc() {
  // List<FormFieldDesc> fieldsDesc = Lists.newArrayList();
  // FormFieldDesc d = null;
  // FormField fann = null;
  //
  // Class<?> clazz = desc.getKlass().toJavaClass();
  // Field[] fields = clazz.getDeclaredFields();
  //
  // for (Field f : fields) {
  // fann = f.getAnnotation(FormField.class);
  // if (fann != null) {
  // d = new FormFieldDesc();
  // d.order = fann.ordinal();
  // d.setName(StringUtils.defaultIfBlank(fann.name(), f.getName()));
  // d.setType(fann.type());
  // fieldsDesc.add(d);
  // }
  // }
  //
  // Collections.sort(fieldsDesc, formFieldDescComparator);
  // return fieldsDesc;
  // }
  //
  // }
  // #################################################################################

  /**
   * 触发生成一个新的Task do_trigger_fullbuild_task
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.DATAFLOW_MANAGE)
  public void doTriggerFullbuildTask(Context context) throws Exception {
    Application app = this.getApplicationDAO().selectByPrimaryKey(this.getAppDomain().getAppid());
    Assert.assertNotNull(app);
    WorkFlow df = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(app.getWorkFlowId());
    Assert.assertNotNull(df);
    this.sendRequest2FullIndexSwapeNode(context, new AppendParams() {

      @Override
      List<PostParam> getParam() {
        return Lists.newArrayList(new PostParam(IFullBuildContext.KEY_WORKFLOW_NAME, df.getName()), new PostParam(IFullBuildContext.KEY_WORKFLOW_ID, String.valueOf(app.getWorkFlowId())), new PostParam(IFullBuildContext.KEY_APP_SHARD_COUNT, String.valueOf(getIndex().getSlices().size())));
      }
    });
  }

  /**
   * 通过taskid查询
   *
   * @param context
   * @throws Exception
   */
  public void doGetWorkflowBuildHistory(final Context context) throws Exception {
    Integer taskid = this.getInt("taskid", null, false);
    WorkFlowBuildHistory buildHistory = this.getWorkflowDAOFacade().getWorkFlowBuildHistoryDAO().selectByPrimaryKey(taskid);
    if (buildHistory == null) {
      throw new IllegalStateException("taskid:" + taskid + "relevant buildHistory can not be null");
    }
    this.setBizResult(context, new ExtendWorkFlowBuildHistory(buildHistory));
  }

  /**
   * 主动触发全量dump
   *
   * @param context
   * @throws Exception
   */
  @Func(PermissionConstant.APP_TRIGGER_FULL_DUMP)
  public void doTriggerDump(final Context context) throws Exception {
    // 'http://localhost:14844/trigger?component.start=indexBackflow&ps=20151215155124'
    // ApplicationCriteria criteria = new ApplicationCriteria();
    // criteria.createCriteria().andProjectNameEqualTo(this.getServiceName());
    // List<Application> appList =
    // this.getApplicationDAO().selectByExample(criteria);
    // Assert.assertEquals(1, appList.size());
    boolean success = sendRequest2FullIndexSwapeNode(context, new AppendParams() {

      @Override
      List<PostParam> getParam() {
        return Collections.emptyList();
      }
    });
    if (success) {
      this.addActionMessage(context, "已经触发了全量DUMP(triggerServiceFullDump)");
    }
  }

  private static final String bizKey = "biz";

  /**
   * @param context
   * @param appendParams
   * @return
   * @throws MalformedURLException
   */
  private boolean sendRequest2FullIndexSwapeNode(final Context context, AppendParams appendParams) throws Exception {
    List<HttpUtils.PostParam> params = appendParams.getParam();
    params.add(new PostParam("appname", this.getCollectionName()));
    return triggerBuild(this, context, params).success;
  }

  public static TriggerBuildResult triggerBuild(BasicModule module, final Context context, List<PostParam> appendParams) throws MalformedURLException {
    // if (1 == 1) {
    // JSONObject o = new JSONObject();
    // o.put("taskid", "425");
    // module.setBizResult(context, o);
    // return new TriggerBuildResult(true);
    // }
    // 增量状态收集节点
    final String incrStateCollectAddress = // reConnect
      ZkUtils.getFirstChildValue(// reConnect
        module.getSolrZkClient(), // reConnect
        ZkUtils.ZK_ASSEMBLE_LOG_COLLECT_PATH, // reConnect
        null, true);
    String assembleNodeIp = StringUtils.substringBefore(incrStateCollectAddress, ":") + ":8080" + Config.CONTEXT_ASSEMBLE;
    TriggerBuildResult triggerResult = HttpUtils.post(new URL("http://" + assembleNodeIp + "/trigger"), appendParams, new PostFormStreamProcess<TriggerBuildResult>() {

      @Override
      public ContentType getContentType() {
        return ContentType.Application_x_www_form_urlencoded;
      }

      @Override
      public TriggerBuildResult p(int status, InputStream stream, Map<String, List<String>> headerFields) {
        TriggerBuildResult triggerResult = null;
        try {
          JSONTokener token = new JSONTokener(stream);
          JSONObject result = new JSONObject(token);
          final String successKey = "success";
          if (result.isNull(successKey)) {
            return new TriggerBuildResult(false);
          }
          triggerResult = new TriggerBuildResult(true);
          if (!result.isNull(bizKey)) {
            JSONObject o = result.getJSONObject(bizKey);
            if (!o.isNull("taskid")) {
              triggerResult.taskid = Integer.parseInt(o.getString("taskid"));
            }
            module.setBizResult(context, o);
          }
          if (result.getBoolean(successKey)) {
            return triggerResult;
          }
          module.addErrorMessage(context, result.getString("msg"));
          return new TriggerBuildResult(false);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    });
    return triggerResult;
  }

  public static class TriggerBuildResult {

    public final boolean success;

    private int taskid;

    public TriggerBuildResult(boolean success) {
      this.success = success;
    }
  }

  // #################################################################################
  public void doGetWorkflow(Context context) throws Exception {
    Integer wfid = this.getInt("wfid");
    Integer taskid = this.getInt("taskid");
    WorkFlow workFlow = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(wfid);
    WorkFlowBuildHistory buildHistory = this.getWorkflowDAOFacade().getWorkFlowBuildHistoryDAO().selectByPrimaryKey(taskid);
    Map<String, Object> result = Maps.newHashMap();
    result.put("workflow", workFlow);
    result.put("task", buildHistory);
    this.setBizResult(context, result);
  }

  /**
   * 构建全量索引歷史記錄
   *
   * @param context
   * @throws Exception
   */
  public void doGetFullBuildHistory(Context context) throws Exception {
    Integer wfid = this.getInt("wfid");
    boolean getwf = this.getBoolean("getwf");
    WorkFlowBuildHistoryCriteria query = new WorkFlowBuildHistoryCriteria();
    // query.createCriteria().andAppIdEqualTo(this.getAppDomain().getAppid());
    query.createCriteria().andWorkFlowIdEqualTo(wfid);
    query.setOrderByClause("id desc");
    WorkFlow workFlow = new WorkFlow();
    if (getwf) {
      workFlow = this.getWorkflowDAOFacade().getWorkFlowDAO().selectByPrimaryKey(wfid);
      if (workFlow == null) {
        throw new IllegalStateException("can not find workflow in db ,wfid:" + wfid);
      }
    }
    IWorkFlowBuildHistoryDAO historyDAO = this.getWorkflowDAOFacade().getWorkFlowBuildHistoryDAO();
    Pager pager = this.createPager();
    pager.setTotalCount(historyDAO.countByExample(query));
    this.setBizResult(context, new PaginationResult(pager, adapterBuildHistory(historyDAO.selectByExample(query, pager.getCurPage(), pager.getRowsPerPage())), workFlow.getName()));
  }

  private List<ExtendWorkFlowBuildHistory> adapterBuildHistory(List<WorkFlowBuildHistory> histories) {
    return histories.stream().map((r) -> {
      return new ExtendWorkFlowBuildHistory(r);
    }).collect(Collectors.toList());
  }

  /**
   * 取得POJO 內容
   *
   * @param context
   * @throws Exception
   */
  public void doGetPojoData(Context context) throws Exception {
    final AppDomainInfo appDomainInfo = this.getAppDomain();
    try (StringWriter writer = new StringWriter()) {
      if (!ViewPojo.downloadResource(context, appDomainInfo, this, writer)) {
        return;
      }
      this.setBizResult(context, writer.toString());
    }
  }

  /**
   * 判断索引是否存在
   *
   * @param context
   * @throws Exception
   */
  public void doGetIndexExist(Context context) throws Exception {
    this.setBizResult(context, true || this.isIndexExist());
  }

  /**
   * Corenodemanage页面初始化的时候页面显示的信息
   *
   * @param context
   * @throws Exception
   */
  public void doGetViewData(Context context) throws Exception {
    Map<String, Object> result = new HashMap<>();
    result.put("instanceDirDesc", getInstanceDirDesc());
    result.put("topology", getCollectionTopology());
    result.put("config", getConfigGroup0());
    result.put("app", this.getAppDomain());
    ClusterStateCollectAction.StatusCollectStrategy collectStrategy = ClusterStateCollectAction.getCollectStrategy(this.clusterSnapshotDAO, this.getAppDomain().getAppid(), ClusterStateCollectAction.TODAY);
    ClusterSnapshot.Summary metricSummary = collectStrategy.getMetricSummary();
    result.put("metrics", metricSummary);
    this.setBizResult(context, result);
  }

  private CollectionTopology getCollectionTopology() throws Exception {
    final QueryResutStrategy queryResutStrategy = QueryIndexServlet.createQueryResutStrategy(this.getAppDomain(), this.getRequest(), this.getResponse(), this.getDaoContext());
    return queryResutStrategy.createCollectionTopology();
    // //
    // URL url = new URL("http://192.168.28.200:8080/solr/admin/zookeeper?_=1587217613386&detail=true&path=%2Fcollections%2Fsearch4totalpay%2Fstate.json&wt=json");
    // return HttpUtils.get(url, new StreamProcess<CollectionTopology>() {
    // @Override
    // public CollectionTopology p(int status, InputStream stream, Map<String, List<String>> headerFields) {
    // JSONTokener tokener = new JSONTokener(stream);
    // JSONObject result = new JSONObject(tokener);
    //
    // result.getString("data");
    //
    //
    // return null;
    // }
    // });
  }

  private ServerGroupAdapter groupConfig;

  private ServerGroupAdapter getConfigGroup0() {
    if (groupConfig == null) {
      groupConfig = this.getServerGroup0();
    }
    return groupConfig;
  }

  /**
   * 该应用下的第0组配置
   *
   * @return
   */
  protected ServerGroupAdapter getServerGroup0() {
    final AppDomainInfo domain = CheckAppDomainExistValve.getAppDomain(this);
    return getServerGroup0(domain, this);
  }

  public static ServerGroupAdapter getServerGroup0(AppDomainInfo domain, BasicModule module) {
    List<ServerGroupAdapter> groupList = BasicScreen.createServerGroupAdapterList(new BasicScreen.ServerGroupCriteriaSetter() {

      @Override
      public void process(ServerGroupCriteria.Criteria criteria) {
        criteria.andAppIdEqualTo(domain.getAppid()).andRuntEnvironmentEqualTo(domain.getRunEnvironment().getId());
        criteria.andGroupIndexEqualTo((short) 0);
        // if (publishSnapshotIdIsNotNull) {
        criteria.andPublishSnapshotIdIsNotNull();
        // }
      }

      @Override
      public List<Server> getServers(RunContext daoContext, ServerGroup group) {
        return Collections.emptyList();
      }

      @Override
      public int getMaxSnapshotId(ServerGroup group, RunContext daoContext) {
        SnapshotCriteria snapshotCriteria = new SnapshotCriteria();
        snapshotCriteria.createCriteria().andAppidEqualTo(group.getAppId());
        return daoContext.getSnapshotDAO().getMaxSnapshotId(snapshotCriteria);
      }
    }, true, module);
    for (ServerGroupAdapter group : groupList) {
      return group;
    }
    return null;
  }

  /**
   * 各个副本节点描述信息
   *
   * @return
   * @throws Exception
   */
  private InstanceDirDesc getInstanceDirDesc() throws Exception {
    InstanceDirDesc dirDesc = new InstanceDirDesc();
    dirDesc.setValid(false);
    DocCollection collection = this.getIndex();
    final Set<String> instanceDir = new HashSet<String>();
    if (collection.getSlices().isEmpty()) {
      dirDesc.setDesc("实例尚未创建,未发现副本节点");
      return dirDesc;
    }
    boolean hasGetAllCount = false;
    URL url = null;
    for (Slice slice : collection.getSlices()) {
      for (final Replica replica : slice.getReplicas()) {
        if (!hasGetAllCount) {
          dirDesc.setAllcount(getAllRowsCount(collection.getName(), replica.getCoreUrl()));
          hasGetAllCount = true;
        }
        url = new URL(replica.getCoreUrl() + "admin/mbeans?stats=true&cat=CORE&key=core&wt=xml");
        // http://192.168.28.200:8080/solr/search4supplyGoods2_shard1_replica_n1/admin/mbeans?stats=true&cat=CORE&key=core&wt=xml
        ConfigFileContext.processContent(url, new StreamProcess<Object>() {

          @Override
          @SuppressWarnings("all")
          public Object p(int s, InputStream stream, Map<String, List<String>> headerFields) {
            SimpleOrderedMap result = (SimpleOrderedMap) RESPONSE_PARSER.processResponse(stream, "utf8");
            final SimpleOrderedMap mbeans = (SimpleOrderedMap) result.get("solr-mbeans");
            SimpleOrderedMap core = (SimpleOrderedMap) ((SimpleOrderedMap) mbeans.get("CORE")).get("core");
            SimpleOrderedMap status = ((SimpleOrderedMap) core.get("stats"));
            String indexDir = StringUtils.substringAfterLast((String) status.get("CORE.indexDir"), "/");
            instanceDir.add(indexDir);
            ReplicState replicState = new ReplicState();
            replicState.setIndexDir(indexDir);
            replicState.setValid(StringUtils.isNotBlank(indexDir));
            if (StringUtils.isBlank(indexDir)) {
              replicState.setInvalidDesc("未完成全量构建");
            }
            replicState.setNodeName(StringUtils.substringBefore(replica.getNodeName(), ":"));
            replicState.setCoreName(StringUtils.substringAfter(replica.getStr(CORE_NAME_PROP), "_"));
            replicState.setState(replica.getState().toString());
            replicState.setLeader(replica.getBool("leader", false));
            dirDesc.addReplicState(slice.getName(), replicState);
            return null;
          }
        });
      }
    }
    final StringBuffer replicDirDesc = new StringBuffer();
    if (instanceDir.size() > 1) {
      replicDirDesc.append("(");
      int count = 0;
      for (String d : instanceDir) {
        replicDirDesc.append(d);
        if (++count < instanceDir.size()) {
          replicDirDesc.append(",");
        }
      }
      replicDirDesc.append(")");
      dirDesc.setDesc("副本目录不一致，分别为:" + replicDirDesc);
    } else if (instanceDir.size() == 1) {
      if (dirDesc.isAllReplicValid()) {
        dirDesc.setValid(true);
        for (String d : instanceDir) {
          dirDesc.setDesc("所有副本目录为:" + d);
          break;
        }
      } else {
        dirDesc.setValid(false);
        dirDesc.setDesc(dirDesc.invalidDesc().toString());
      }
    } else {
      dirDesc.setDesc("副本目录数异常,size:" + instanceDir.size());
    }
    return dirDesc;
  }

  public static long getAllRowsCount(String collectionName, String coreURL) throws SolrServerException, IOException {
    QueryCloudSolrClient solrClient = new QueryCloudSolrClient(coreURL);
    SolrQuery query = new SolrQuery();
    query.setRows(0);
    query.setQuery("*:*");
    return solrClient.query(collectionName, query).getResults().getNumFound();
  }

  /**
   * 删除增量通道
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_MANAGE)
  public void doIncrDelete(Context context) throws Exception {
    TISK8sDelegate k8sDelegate = TISK8sDelegate.getK8SDelegate(this.getCollectionName());
    // 删除增量实例
    k8sDelegate.removeIncrProcess();
    // PluginStore<IncrStreamFactory> incrPluginStore = TIS.getPluginStore(this.getCollectionName(), IncrStreamFactory.class);
    // IncrStreamFactory streamFactory = incrPluginStore.getPlugin();
    // IIncrSync incrSync = streamFactory.getIncrSync();
    // incrSync.removeInstance(this.getCollectionName());
  }

  /**
   * 控制增量任务暂停继续，當增量任務需要重啟或者過載的情况下需要重启增量执行节点，需要先将管道中的数据全部排空
   *
   * @param context
   * @throws Exception
   */
  @Func(value = PermissionConstant.PERMISSION_INCR_PROCESS_MANAGE)
  public void doIncrResumePause(Context context) throws Exception {
    boolean pause = this.getBoolean("pause");
    final String collection = this.getAppDomain().getAppName();
    JobType.RemoteCallResult<?> callResult = JobType.IndexJobRunning.assembIncrControl(collection, Lists.newArrayList(new PostParam("stop", pause)), null);
    if (!callResult.success) {
      this.addErrorMessage(context, callResult.msg);
      return;
    }
    this.addActionMessage(context, collection + ":增量任务状态变为:" + (pause ? "暂停" : "启动"));
  }

  //

  /**
   * 创建一个应用,选择组×组内副本数目
   */
  @Func(PermissionConstant.APP_INITIALIZATION)
  public void doCreateCoreSetp1(Context context) throws Exception {
    final Integer groupNum = this.getInt("group");
    final Integer repliation = this.getInt("replica");
    if (groupNum == null || groupNum < 1) {
      this.addErrorMessage(context, "组数不能小于1");
      return;
    }
    if (repliation == null || repliation < 1) {
      this.addErrorMessage(context, "组内副本数不能小于1");
      return;
    }
    context.put(CREATE_CORE_SELECT_COREINFO, new CreateCorePageDTO(groupNum, repliation, "y".equals(this.getString("exclusive"))));
    // this.forward("coredefine.vm");
  }

  public static class CreateCorePageDTO {

    private final int groupNum;

    private final int replication;

    private final int assignGroupCount;

    private final boolean excludeHaveAppServer;

    /**
     * @param groupNum
     * @param replication
     * @param excludeHaveAppServers 是否要排除已经部署应用的服务器
     */
    public CreateCorePageDTO(int groupNum, int replication, boolean excludeHaveAppServers) {
      this(0, groupNum, replication, excludeHaveAppServers);
    }

    public CreateCorePageDTO(int assignGroupCount, int groupNum, int replication, boolean excludeHaveAppServers) {
      super();
      this.groupNum = groupNum;
      this.replication = replication;
      this.assignGroupCount = assignGroupCount;
      // 说明该应用是否是独占的
      this.excludeHaveAppServer = excludeHaveAppServers;
    }

    public int getAssignGroupCount() {
      return assignGroupCount;
    }

    public int getGroupNum() {
      return groupNum;
    }

    public int getReplication() {
      return replication;
    }

    public boolean isExcludeHaveAppServers() {
      return excludeHaveAppServer;
    }

    public int[] getGroupArray() {
      int[] result = new int[groupNum];
      for (int i = 0; i < groupNum; i++) {
        result[i] = assignGroupCount + i;
      }
      return result;
    }
  }

  /**
   * 创建一个应用
   */
  @SuppressWarnings("all")
  @Func(PermissionConstant.APP_INITIALIZATION)
  public void doCreateCore(final Context context) throws Exception {
    final Integer groupNum = this.getInt("group");
    // 最大组内副本数目 改过了
    final Integer repliation = this.getInt("replica");
    if (groupNum == null || groupNum < 1) {
      this.addErrorMessage(context, "组数不能小于1");
      return;
    }
    if (repliation == null || repliation < 1) {
      this.addErrorMessage(context, "组内副本数不能小于1");
      return;
    }
    FCoreRequest request = createCoreRequest(context, groupNum, repliation, "创建", true);
    this.createCollection(this, context, groupNum, repliation, request, this.getServerGroup0().getPublishSnapshotId());
  }

  /**
   * 创建集群索引
   *
   * @param module
   * @param context
   * @param groupNum
   * @param repliationCount
   * @param request
   * @param publishSnapshotId
   * @throws Exception
   * @throws MalformedURLException
   * @throws UnsupportedEncodingException
   */
  public static boolean createCollection(BasicModule module, final Context context, final Integer groupNum, final Integer repliationCount, FCoreRequest request, int publishSnapshotId) throws Exception, MalformedURLException, UnsupportedEncodingException {
    if (publishSnapshotId < 0) {
      throw new IllegalArgumentException("shall set publishSnapshotId");
    }
    if (!request.isValid()) {
      return false;
    }

    SnapshotDomain snapshotDomain = module.getSnapshotViewDAO().getView(publishSnapshotId);
    InputStream input = null;
    IIndexMetaData meta = SolrFieldsParser.parse(() -> snapshotDomain.getSolrSchema().getContent());
    SolrFieldsParser.ParseResult parseResult = meta.getSchemaParseResult();
    String routerField = parseResult.getSharedKey();
    if (StringUtils.isBlank(routerField)) {
      module.addErrorMessage(context, "Schema中还没有设置‘sharedKey’");
      return false;
    }
    final String cloudOverseer = getCloudOverseerNode(module.getSolrZkClient());
    // : "plain";
    final String routerName = "strhash";
    URL url = new URL("http://" + cloudOverseer + "/solr/admin/collections?action=CREATE&name=" + request.getIndexName() + "&router.name=" + routerName + "&router.field=" + routerField + "&replicationFactor=" + repliationCount + "&numShards=" + groupNum + "&collection.configName=" + DEFAULT_SOLR_CONFIG + "&maxShardsPerNode=" + MAX_SHARDS_PER_NODE + "&property.dataDir=data&createNodeSet=" + URLEncoder.encode(request.getCreateNodeSet(), getEncode()) + "&" + PropteryGetter.KEY_PROP_CONFIG_SNAPSHOTID + "=" + publishSnapshotId);
    log.info("create new cloud url:" + url);
    HttpUtils.processContent(url, new StreamProcess<Object>() {

      @Override
      public Object p(int status, InputStream stream, Map<String, List<String>> headerFields) {
        ProcessResponse result = null;
        if ((result = ProcessResponse.processResponse(stream, (err) -> module.addErrorMessage(context, err))).success) {
          module.addActionMessage(context, "成功触发了创建索引集群" + groupNum + "组,组内" + repliationCount + "个副本");
        }
        return null;
      }
    });
    return true;
  }

  private static String getCloudOverseerNode(TisZkClient zkClient) throws Exception {
    Map v = JSON.parseObject(zkClient.getData("/overseer_elect/leader", null, new Stat(), true), Map.class, Feature.AllowUnQuotedFieldNames);
    String id = (String) v.get("id");
    if (id == null) {
      throw new IllegalStateException("collection cluster overseer node has not launch");
    }
    String[] arr = StringUtils.split(id, "-");
    if (arr.length < 3) {
      throw new IllegalStateException("overseer ephemeral node id:" + id + " is illegal");
    }
    return StringUtils.substringBefore(arr[1], "_");
  }

  /**
   * 更新全部schema
   *
   * @param context
   * @throws Exception
   */
  @Func(PermissionConstant.APP_SCHEMA_UPDATE)
  public void doUpdateSchemaAllServer(final Context context) throws Exception {
    final boolean needReload = this.getBoolean("needReload");
    Integer snapshotId = this.getInt(ICoreAdminAction.TARGET_SNAPSHOT_ID);
    DocCollection docCollection = TISZkStateReader.getCollectionLive(this.getZkStateReader(), this.getCollectionName());
    pushConfig2Engine(this, context, docCollection, snapshotId, needReload);
  }

  public static void pushConfig2Engine(BasicModule module, Context context, DocCollection collection, int snapshotId, boolean needReload) throws Exception {
    // module.errorsPageShow(context);
    if (traverseCollectionReplic(collection, false, /* collection */
      new ReplicaCallback() {

        @Override
        public boolean process(boolean isLeader, final Replica replica) throws Exception {
          try {
            URL url = new URL(replica.getStr(BASE_URL_PROP) + "/admin/cores?action=CREATEALIAS&" + ICoreAdminAction.EXEC_ACTION + "=" + ICoreAdminAction.ACTION_UPDATE_CONFIG + "&core=" + replica.getStr(CORE_NAME_PROP) + "&" + ZkStateReader.COLLECTION_PROP + "=" + collection.getName() + "&needReload=" + needReload + "&" + ICoreAdminAction.TARGET_SNAPSHOT_ID + "=" + snapshotId);
            return HttpUtils.processContent(url, new StreamProcess<Boolean>() {

              @Override
              public Boolean p(int status, InputStream stream, Map<String, List<String>> headerFields) {
                ProcessResponse result = null;
                if ((result = ProcessResponse.processResponse(stream, (err) -> module.addErrorMessage(context, replica.getName() + "," + err))).success) {
                  // addActionMessage(context, "成功触发了创建索引集群" + groupNum + "组,组内" + repliationCount + "个副本");
                  // return true;
                }
                return true;
              }
            });
          } catch (MalformedURLException e) {
            // e.printStackTrace();
            log.error(collection.getName(), e);
            module.addErrorMessage(context, e.getMessage());
          }
          return true;
        }
      })) {
      // module.addActionMessage(context, "已经更新全部服务器配置文件");
    }
  }

  /**
   * 遍历所有的副本节点
   *
   * @param collection
   * @param firstProcessLeader
   * @param replicaCallback
   * @return
   * @throws Exception
   */
  public static boolean traverseCollectionReplic(final DocCollection collection, boolean firstProcessLeader, ReplicaCallback replicaCallback) throws Exception {
    if (collection == null) {
      throw new IllegalStateException("param collection can not be null");
    }
    Replica leader = null;
    // String requestId = null;
    for (Slice slice : collection.getSlices()) {
      leader = slice.getLeader();
      if (firstProcessLeader) {
        if (!replicaCallback.process(true, leader)) {
          return false;
        }
      }
      for (Replica replica : slice.getReplicas()) {
        if (leader == replica) {
          continue;
        }
        if (!replicaCallback.process(true, replica)) {
          return false;
        }
      }
      if (!firstProcessLeader) {
        if (!replicaCallback.process(true, leader)) {
          return false;
        }
      }
    }
    return true;
  }

  public static interface ReplicaCallback {

    public boolean process(boolean isLeader, Replica replica) throws Exception;
  }

  //
  // /**
  // * 设置这次操作的json描述
  // *
  // * @param request
  // * @throws Exception
  // */
  // // private void setOperationLogDesc(FCoreRequest request) throws
  // Exception {
  // // JSONObject json = new JSONObject();
  // // json.put("name", request.getRequest().getServiceName());
  // // json.put("terminatorUrl", request.getRequest().getTerminatorUrl());
  // // json.put("monopolized", request.getRequest().isMonopolized());
  // //
  // // JSONObject servers = new JSONObject();
  // //
  // // for (Map.Entry<Integer, Collection<String>> entry : request
  // // .getServersView().entrySet()) {
  // // JSONArray group = new JSONArray();
  // //
  // // for (String ip : entry.getValue()) {
  // // group.put(ip);
  // // }
  // // servers.put("group" + entry.getKey(), group);
  // // }
  // // json.put("servers", servers);
  // // request.getRequest().setOpDesc(json.toString());
  // // request.getRequest().setLoggerContent(StringUtils.EMPTY);
  // //
  // // }
  //
  // private Map<String, CoreNode> getCoreNodeMap() {
  // return getCoreNodeMap(/* getCoreNodeMap */false);
  //
  // }
  //
  // /**
  // * 取得当前应用
  // *
  // * @return
  // */
  private Map<String, CoreNode> getCoreNodeMap(boolean isAppNameAware) {
    CoreNode[] nodelist = SelectableServer.getCoreNodeInfo(this.getRequest(), this, false, isAppNameAware);
    Map<String, CoreNode> result = new HashMap<String, CoreNode>();
    for (CoreNode node : nodelist) {
      result.put(node.getNodeName(), node);
    }
    return result;
  }

  //
  // /**
  // * @param context
  // * @param serverSuffix
  // * @param isAppNameAware
  // * @param mustSelectMoreOneReplicAtLeast
  // * 每一组至少选一个副本（在创建core的时候，每组至少要选一个以上的副本， <br/>
  // * 但是在减少副本的时候每组可以一个副本都不选）
  // * @return
  // */
  private ParseIpResult parseIps(Context context, String serverSuffix, boolean isAppNameAware, boolean mustSelectMoreOneReplicAtLeast) {
    ParseIpResult result = new ParseIpResult();
    result.valid = false;
    List<String> parseips = new ArrayList<String>();
    String[] ips = this.getRequest().getParameterValues("selectedServer" + StringUtils.trimToEmpty(serverSuffix));
    if (ips == null) {
      return result;
    }
    if (mustSelectMoreOneReplicAtLeast && ips.length < 1) {
      addErrorMessage(context, "请" + (StringUtils.isNotEmpty(serverSuffix) ? "为第" + serverSuffix + "组" : StringUtils.EMPTY) + "选择服务器");
      return result;
    }
    // StringBuffer ipLiteria = new StringBuffer();
    result.ipLiteria.append("[");
    Matcher matcher = null;
    final Map<String, CoreNode> serverdetailMap = getCoreNodeMap(isAppNameAware);
    CoreNode nodeinfo = null;
    CoreNode current = null;
    for (String ip : ips) {
      matcher = this.isValidIpPattern(ip);
      if (!matcher.matches()) {
        this.addErrorMessage(context, "IP:" + ip + "不符合格式规范");
        return result;
      }
      // ▼▼▼ 校验组内服务器lucene版本是否一致
      current = serverdetailMap.get(ip);
      if (current == null) {
        this.addErrorMessage(context, "服务器" + ip + "，不在可选集合之内");
        return result;
      }
      if (nodeinfo != null) {
        if (current.getLuceneSpecVersion() != nodeinfo.getLuceneSpecVersion()) {
          this.addErrorMessage(context, (StringUtils.isNotEmpty(serverSuffix) ? "第" + serverSuffix + "组" : StringUtils.EMPTY) + "服务器Lucene版本不一致");
          return result;
        }
      }
      nodeinfo = current;
      // ▲▲▲ 校验组内服务器lucene版本是否一致
      // matcher.group(group)
      parseips.add(matcher.group(0));
      result.ipLiteria.append(ip).append(",");
    }
    result.ipLiteria.append("]");
    result.ips = parseips.toArray(new String[parseips.size()]);
    result.valid = true;
    return result;
  }

  //
  private static class ParseIpResult {

    private boolean valid;

    private String[] ips;

    private final StringBuffer ipLiteria = new StringBuffer();
  }

  /**
   * @param context
   * @param appName
   * @param ips     标准的ip格式
   * @return
   */
  public static CoreRequest createIps(Context context, final String appName, String[] ips) {
    CoreRequest request = new CoreRequest();
    request.setIncludeIps(ips);
    request.setServiceName(appName);
    request.runtime = RunEnvironment.getSysRuntime();
    log.debug("request.getRunEnv():" + request.getRunEnv());
    return request;
  }

  public static class CoreRequest {

    private String[] ips;

    private String serviceName;

    // private boolean monopolized;
    private RunEnvironment runtime;

    public void addNodeIps(String groupIndex, String ip) {
    }

    public void setIncludeIps(String[] ips) {
      this.ips = ips;
    }

    public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
    }

    public String getServiceName() {
      return serviceName;
    }

    // public void setMonopolized(boolean monopolized) {
    // this.monopolized = monopolized;
    // }
    public RunEnvironment getRunEnv() {
      return this.runtime;
    }
  }

  private abstract static class AppendParams {

    abstract List<HttpUtils.PostParam> getParam();
  }

  /**
   * 索引回流
   *
   * @param context
   * @throws Exception
   */
  @Func(PermissionConstant.APP_TRIGGER_FULL_DUMP)
  public void doTriggerSynIndexFile(Context context) throws Exception {
    final String userpoint = this.getString("userpoint");
    if (StringUtils.isBlank(userpoint)) {
      this.addErrorMessage(context, "请填写userpoint，格式：admin#yyyyMMddHHmmss");
      return;
    }
    boolean success = triggerBuild("indexBackflow", context, userpoint);
    if (success) {
      this.addActionMessage(context, "已经触发了userpoint回流" + StringUtils.defaultIfEmpty(userpoint, "-1"));
    }
  }

  /**
   * @param context
   * @param userpoint
   * @return
   * @throws Exception
   */
  private boolean triggerBuild(final String startPhrase, Context context, final String userpoint) throws Exception {
    String ps = null;
    String user = null;
    if (StringUtils.indexOf(userpoint, "#") > -1) {
      ps = StringUtils.substringAfter(userpoint, "#");
      user = StringUtils.substringBefore(userpoint, "#");
    } else {
      ps = userpoint;
    }
    // TODO 应该索引数据是否存在
    final String pps = ps;
    final String fuser = user;
    boolean success = sendRequest2FullIndexSwapeNode(context, new AppendParams() {

      @Override
      List<PostParam> getParam() {
        List<HttpUtils.PostParam> params = Lists.newArrayList();
        params.add(new PostParam(IParamContext.COMPONENT_START, startPhrase));
        params.add(new PostParam(IParamContext.COMPONENT_START, pps));
        // "&ps=" + ps);
        if (StringUtils.isNotBlank(fuser)) {
          // param.append("&user=").append(fuser);
          params.add(new PostParam("user", fuser));
        }
        return params;
      }
    });
    return success;
  }

  /**
   * 触发索引build（不需要dump）
   *
   * @param context
   * @throws Exception
   */
  @Func(PermissionConstant.APP_TRIGGER_FULL_DUMP)
  public void doTriggerFullDumpFile(Context context) throws Exception {
    final String userpoint = this.getString("userpoint");
    if (StringUtils.isBlank(userpoint)) {
      this.addErrorMessage(context, "请填写userpoint，格式：admin#yyyyMMddHHmmss");
      return;
    }
    boolean success = triggerBuild("indexBuild", context, userpoint);
    if (success) {
      addActionMessage(context, "已经触发了triggerFullDumpFile,userpoint:" + StringUtils.defaultIfEmpty(userpoint, "-1"));
    }
  }

  //
  // // private TriggerJobConsole triggerJobConsole;
  //
  // // @Autowired
  // // public final void setTriggerJobConsole(TriggerJobConsole
  // // triggerJobConsole) {
  // // this.triggerJobConsole = triggerJobConsole;
  // // }
  //
  // /**
  // * 校验dump是否正在执行
  // *
  // * @return
  // */
  // private boolean isDumpWorking(Context context) {
  // // try {
  // //
  // // String coreName = this.getAppDomain().getAppName();
  // //
  // // boolean pause = triggerJobConsole.isPause(coreName);
  // //
  // // if (!pause) {
  // // this.addErrorMessage(context, "当前应用：" + coreName
  // // + "有Dump Job 正在执行，请确认已经停止后再执行该操作");
  // // }
  // //
  // // return !pause;
  // //
  // // } catch (RemoteException e) {
  // // throw new RuntimeException(e);
  // // }
  // return false;
  // }
  //
  // private String getServiceName() {
  // return this.getAppDomain().getAppName();
  // }
  //
  // /**
  // * 更新某一组的schema文件
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_SCHEMA_UPDATE)
  // public void doUpdateSchemaByGroup(Context context) throws Exception {
  //
  // Integer group = this.getInt("group");
  // // Assert.assertNotNull(group);
  // if (group == null || group < 0) {
  // this.addErrorMessage(context, "请设置组");
  // return;
  // }
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // this.getClientProtocol().schemaChange(getServiceName(), group);
  // this.addActionMessage(context, "触发第" + group + "组Schema文件更新成功");
  // }
  //
  // /**
  // * 更新所有的solrconfig
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_SOLRCONFIG_UPDATE)
  // public void doUpdateSolrconfigAllServer(Context context) throws Exception
  // {
  // if (isDumpWorking(context)) {
  // return;
  // }
  // this.getClientProtocol().coreConfigChange(this.getServiceName());
  //
  // this.addActionMessage(context, "已经成功触发了更新全部Solrconfig");
  // }
  //
  // /**
  // * 更新solrconfig
  // *
  // * @param context
  // * @throws Exception
  // */
  // // doUpdateSchemaByGroup
  // @Func(PermissionConstant.APP_SOLRCONFIG_UPDATE)
  // public void doUpdateSolrconfigByGroup(Context context) throws Exception {
  //
  // Integer group = this.getInt("group");
  // if (group == null || group < 0) {
  // this.addErrorMessage(context, "请设置组");
  // return;
  // }
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // this.getClientProtocol().coreConfigChange(this.getServiceName(), group);
  // this.addActionMessage(context, "触发第" + group + "组solrconfig文件更新成功");
  // }
  //
  // private static final Pattern serverPattern = Pattern
  // .compile("(.+?)_(\\d+)");
  //
  // /**
  // * 具体更新某一个服务器
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_SOLRCONFIG_UPDATE)
  // public void doUpdateSolrConfigByServer(Context context) throws Exception
  // {
  // // Integer group = this.getInt("group");
  // // Assert.assertNotNull(group);
  // //
  // // String server = this.getString("server");
  // // Assert.assertNotNull(group);
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // updateResourceByServer(context, new ResourceConfigRefesh() {
  //
  // @Override
  // public void update(String ip, Integer group, Context context)
  // throws Exception {
  // getClientProtocol().coreConfigChange(getServiceName(), group,
  // ip);
  // addActionMessage(context, "已经触发了服务器：" + ip + ",第" + group
  // + " 组的 solrconfig更新");
  // }
  //
  // });
  //
  // }
  //
  // private void updateResourceByServer(Context context,
  // ResourceConfigRefesh resourceRefesh) throws Exception {
  // String[] servers = this.getRequest().getParameterValues("updateServer");
  //
  // if (servers == null || servers.length < 1) {
  // this.addErrorMessage(context, "请选择服务器");
  // return;
  // }
  // Matcher matcher = null;
  // String ip = null;
  // Integer group = null;
  // for (String server : servers) {
  // matcher = serverPattern.matcher(server);
  //
  // if (!matcher.matches()) {
  // this.addErrorMessage(context, "传递参数updateServer 不合法：" + server);
  // return;
  // }
  //
  // group = Integer.parseInt(matcher.group(2));
  //
  // matcher = this.isValidIpPattern(matcher.group(1));
  // if (!matcher.matches()) {
  // this.addErrorMessage(context, "IP:" + ip + "不符合格式规范");
  // return;
  // }
  //
  // ip = matcher.group(1);
  //
  // resourceRefesh.update(ip, group, context);
  //
  // }
  // }
  //
  // private interface ResourceConfigRefesh {
  // public void update(String ip, Integer group, Context context)
  // throws Exception;
  //
  // }
  //
  // // do_update_hsf_all_server
  // @Func(PermissionConstant.APP_HSF_UPDATE)
  // public void doUpdateHsfAllServer(Context context) throws Exception {
  //
  // this.getClientProtocol().rePublishHsf(this.getServiceName());
  //
  // this.addActionMessage(context, "已经成功触发了所有hsf重新发布");
  // }
  //
  // // doUpdateSolrconfigByGroup
  // @Func(PermissionConstant.APP_HSF_UPDATE)
  // public void doUpdateHsfByGroup(Context context) throws Exception {
  //
  // Integer group = this.getInt("group");
  // if (group == null || group < 0) {
  // this.addErrorMessage(context, "请设置组号");
  // return;
  // }
  //
  // this.getClientProtocol().rePublishHsf(this.getServiceName(), group);
  // this.addActionMessage(context, "触发第" + group + "组Hsf服务更新成功");
  // }
  //
  // /**
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_HSF_UPDATE)
  // public void doUpdateHsfByServer(Context context) throws Exception {
  // updateResourceByServer(context, new ResourceConfigRefesh() {
  // @Override
  // public void update(String ip, Integer group, Context context)
  // throws Exception {
  // getClientProtocol().republishHsf(getServiceName(), group, ip);
  // addActionMessage(context, "已经触发服务器[" + ip + "]HSF服务重新发布");
  // }
  // });
  // // Integer group = this.getInt("group");
  // // // Assert.assertNotNull(group);
  // // if (group == null || group < 1) {
  // // this.addErrorMessage(context, "请设置组号");
  // // return;
  // // }
  // //
  // // final String server = this.getString("server");
  // // // Matcher m = PATTERN_IP.matcher(StringUtils.trimToEmpty(server));
  // // if (isValidIpPattern(server)) {
  // // this.addErrorMessage(context, "请设置组号");
  // // return;
  // // }
  // //
  // // this.getClientProtocol().republishHsf(this.getServiceName(), group,
  // // server);
  // //
  // // this.addActionMessage(context, "已经触发服务器[" + server + "]HSF服务重新发布");
  //
  // }
  //
  // /**
  // *
  // * @return
  // */
  private Matcher isValidIpPattern(String server) {
    return PATTERN_IP.matcher(StringUtils.trimToEmpty(server));
    // return (m.matches());
  }

  //
  // /**
  // * 减少组内副本数目
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_REPLICA_MANAGE)
  // public void doDecreaseReplica(Context context) throws Exception {
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // // this.getClientProtocol().desCoreReplication(request, replication);
  // updateReplica(context, new ReplicaUpdate() {
  // @Override
  // public String getExecuteLiteria() {
  // return "减少";
  // }
  //
  // @Override
  // public void update(CoreRequest request, short[] replicCount)
  // throws IOException {
  // getClientProtocol().desCoreReplication(request, replicCount);
  // }
  //
  // // @Override
  // // public void update(CoreRequest corerequest, Integer replica)
  // // throws IOException {
  // //
  // // }
  //
  // @Override
  // public boolean shallContinueProcess(Context context,
  // FCoreRequest request) {
  // // 删除的副本中如果有master节点的话就不能删除
  // boolean canReduceReplica = !getClientProtocol()
  // .hasRealTimeLeaderServer(
  // CoreAction.this.getServiceName(),
  // request.getIps());
  // if (!canReduceReplica) {
  // CoreAction.this.addErrorMessage(context, "因为被删除的机器"
  // + request.getIps()
  // + "中有实时模式的master节点，master节点不能被删除");
  // }
  //
  // return canReduceReplica;
  // }
  //
  // });
  // }
  //
  // /**
  // * 添加组内副本数目
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_REPLICA_MANAGE)
  // public void doAddReplica(Context context) throws Exception {
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // updateReplica(context, new ReplicaUpdate() {
  // @Override
  // public String getExecuteLiteria() {
  // return "添加";
  // }
  //
  // @Override
  // public void update(CoreRequest request, short[] replicCount)
  // throws IOException {
  // getClientProtocol().addCoreReplication(request, replicCount);
  // }
  // });
  // }
  //
  // /**
  // * 更新组内副本数，用于增加或者减少副本
  // *
  // * @param context
  // * @param replicaUpdate
  // * @throws Exception
  // */
  // private void updateReplica(Context context, ReplicaUpdate replicaUpdate)
  // throws Exception {
  // final Integer replica = this.getInt("replica");
  // Integer group = this.getInt("groupcount");
  //
  // if (replica == null || replica < 1) {
  // this.addErrorMessage(context, "请设置合法的组内副本数");
  // return;
  // }
  // if (group == null || group < 1) {
  // this.addErrorMessage(context, "请设置组数");
  // return;
  // }
  //
  // if (!this.getCoreManager().isCreateNewServiceSuc(this.getServiceName()))
  // {
  // this.addErrorMessage(context, "该Solr应用正在创建索引中，请等待创建成功之后再设置");
  // return;
  // }
  //
  // FCoreRequest request = createCoreRequest(context, 0, group, replica,
  // replicaUpdate.getExecuteLiteria(),/* isAppNameAware */true, /*
  // mustSelectMoreOneReplicAtLeast */
  // false);
  //
  // // FCoreRequest request = createCoreRequest(context, group, replica,
  // // replicaUpdate.getExecuteLiteria(), false/*
  // // mustSelectMoreOneReplicAtLeast */);
  //
  // if (!request.isValid()
  // && replicaUpdate.shallContinueProcess(context, request)) {
  // return;
  // }
  //
  // // 编辑操作日志
  // // setOperationLogDesc(request);
  //
  // replicaUpdate.update(request.getRequest(), request.getReplicCount());
  // this.addActionMessage(context, replicaUpdate.getExecuteLiteria()
  // + "副本成功!");
  // }
  //
  private FCoreRequest createCoreRequest(Context context, Integer group, Integer replica, String setVerbs, boolean mustSelectMoreOneReplicAtLeast) {
    return createCoreRequest(context, 0, group, replica, setVerbs, mustSelectMoreOneReplicAtLeast);
  }

  private FCoreRequest createCoreRequest(Context context, int assignGroupCount, int group, int replica, String setVerbs, boolean mustSelectMoreOneReplicAtLeast) {
    return createCoreRequest(context, assignGroupCount, group, replica, setVerbs, /* isAppNameAware */
      false, mustSelectMoreOneReplicAtLeast);
  }

  /**
   * @param context
   * @param assignGroupCount 已经分配到的group数量
   * @param group            添加的组数
   * @param replica
   * @param setVerbs
   * @return
   */
  private FCoreRequest createCoreRequest(Context context, int assignGroupCount, int group, int replica, String setVerbs, boolean isAppNameAware, boolean mustSelectMoreOneReplicAtLeast) {
    // boolean valid = false;
    // CoreRequest request = ;
    CoreRequest coreRequest = createIps(context, this.getCollectionName(), null);
    FCoreRequest result = new FCoreRequest(coreRequest, assignGroupCount + group, assignGroupCount);
    // StringBuffer addserverSummary = new StringBuffer();
    // 不用为单独的一个组设置服务ip地址
    final int GROUP_SIZE = 1;
    for (int i = 0; i < GROUP_SIZE; i++) {
      ParseIpResult parseResult = parseIps(context, String.valueOf(assignGroupCount + i), isAppNameAware, mustSelectMoreOneReplicAtLeast);
      if (parseResult.valid && parseResult.ips.length > 0) {
        if ((parseResult.ips.length * MAX_SHARDS_PER_NODE) < (group * replica)) {
          this.addErrorMessage(context, "您选择的机器节点不足,至少要" + ((group * replica) / MAX_SHARDS_PER_NODE) + "台机器");
          // if (replica < parseResult.ips.length) {
          // this.addErrorMessage(context, "第" + (assignGroupCount +
          // i)
          // + "组，" + setVerbs + "副本数目最大为" + replica + "台");
        } else {
          // for (int j = 0; j < replica; j++) {
          for (int j = 0; j < parseResult.ips.length; j++) {
            result.addNodeIps((assignGroupCount + i), parseResult.ips[j]);
          }
          // addserverSummary.append()
          // this.addActionMessage(context, "第" + (assignGroupCount +
          // i)
          // + "组，" + setVerbs + "副本机为" + parseResult.ipLiteria);
          this.addActionMessage(context, "选择的机器为:" + parseResult.ipLiteria);
          result.setValid(true);
        }
      }
    }
    if (!result.isValid()) {
      this.addErrorMessage(context, "请至少为任何一组添加一个以上副本节点");
    }
    return result;
  }

  @Autowired
  public void setClusterSnapshotDAO(IClusterSnapshotDAO clusterSnapshotDAO) {
    this.clusterSnapshotDAO = clusterSnapshotDAO;
  }
  // private static abstract class ReplicaUpdate {
  // /**
  // * @param request
  // * @param replicCount
  // * 每组添加的副本数目
  // * @throws IOException
  // */
  // public abstract void update(CoreRequest request, short[] replicCount)
  // throws IOException;
  //
  // public abstract String getExecuteLiteria();
  //
  // public boolean shallContinueProcess(Context context, FCoreRequest
  // request) {
  // return true;
  // }
  //
  // }
  //
  // /**
  // * 更新组内数目
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_REPLICA_MANAGE)
  // public void doUpdateGroupCountSetp1(Context context) throws Exception {
  // Integer groupcount = this.getInt("addgroup");
  // if (groupcount == null || groupcount < 1) {
  // this.addErrorMessage(context, "请设置合法的Group数");
  // return;
  // }
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // // 校验是否是实时模式
  // if (isRealTimeModel(context)) {
  // return;
  // }
  //
  // this.forward("addgroup");
  //
  // LocatedCores locateCore = this.getClientProtocol().getCoreLocations(
  // this.getAppDomain().getAppName());
  //
  // context.put("assignGroupCount", locateCore.getCores().size());
  // int serverSum = 0;
  // for (LocatedCore localtedCore : locateCore.getCores()) {
  // serverSum += localtedCore.getCoreNodeInfo().length;
  // }
  // int replica = 0;
  // try {
  // replica = (serverSum / locateCore.getCores().size());
  // } catch (Throwable e) {
  //
  // }
  //
  // if (replica < 1) {
  // this.addErrorMessage(context, "应用有异常，组内副本数部门小于1");
  // return;
  // }
  //
  // context.put("assignreplica", replica);
  //
  // context.put(CoreAction.CREATE_CORE_SELECT_COREINFO,
  // new CreateCorePageDTO(locateCore.getCores().size(), groupcount,
  // replica, false// locateCore.isMonopy()
  // ));
  //
  // }
  //
  // /**
  // * 更新组内数目
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_REPLICA_MANAGE)
  // public void doUpdateGroupCount(Context context) throws Exception {
  // Integer groupcount = this.getInt("group");
  // if (groupcount == null || groupcount < 1) {
  // this.addErrorMessage(context, "请设置合法的Group数");
  // return;
  // }
  //
  // Integer assignGroupCount = this.getInt("assigngroup");
  //
  // if (assignGroupCount == null) {
  // throw new IllegalArgumentException(
  // "assignGroupCount can not be null");
  // }
  //
  // final Integer replica = this.getInt("replica");
  //
  // if (replica == null) {
  // throw new IllegalArgumentException("replica can not be null");
  // }
  //
  // if (isRealTimeModel(context)) {
  // return;
  // }
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // FCoreRequest result = createCoreRequest(context, assignGroupCount,
  // groupcount, replica, "添加",/* isAppNameAware */true);
  //
  // if (!result.isValid()) {
  // return;
  // }
  //
  // this.getClientProtocol().addCoreNums(result.getRequest(),
  // groupcount.shortValue(), result.getReplicCount());
  //
  // this.addActionMessage(context, "添加" + groupcount + "组服务器成功!!!!!");
  // }
  //
  // private boolean isRealTimeModel(Context context) throws Exception {
  // LocatedCores locateCore = this.getClientProtocol().getCoreLocations(
  // this.getAppDomain().getAppName());
  //
  // if (Corenodemanage.isRealTime(locateCore)) {
  // this.addErrorMessage(context, "实时应用不能添加组");
  // return true;
  // }
  //
  // return false;
  // }
  //
  // /**
  // * 从应用core中删除一个服务器
  // *
  // * @param context
  // * @throws Exception
  // */
  // @Func(PermissionConstant.APP_SERVER_SET)
  // public void doDeleteServerFromCore(Context context) throws Exception {
  //
  // Integer group = this.getInt("group");
  //
  // if (group == null || group < 0) {
  // this.addErrorMessage(context, "请选择合法的Group");
  // return;
  // }
  //
  // final String ip = this.getString("ip");
  // if (StringUtils.isEmpty(ip)) {
  // this.addErrorMessage(context, "请选择需要删除的服务IP地址");
  // return;
  // }
  //
  // Matcher matcher = this.isValidIpPattern(ip);
  // if (!matcher.matches()) {
  // this.addErrorMessage(context, "IP:" + ip + "不符合格式规范");
  // return;
  // }
  //
  // if (isDumpWorking(context)) {
  // return;
  // }
  //
  // // 将正在运行的副本删除
  // this.getClientProtocol().unprotectProcessExcessReplication(
  // this.getServiceName(), group, matcher.group(1));
  //
  // this.addActionMessage(context, "已经触发删除server为：" + ip + " 的第" + group
  // + "组服务器，页面状态需要稍后才能同步");
  // }
  //
  // public void doStopOne(Context context) throws Exception {
  // int groupNum = this.getInt("groupNum");
  // int replicaNum = this.getInt("replicaNum");
  // boolean success = false;
  // try {
  // success = this.getClientProtocol().stopOne(
  // this.getAppDomain().getAppName(), groupNum, replicaNum);
  // } catch (Exception e) {
  // }
  // if (success) {
  // this.addActionMessage(context, "成功触发停止副本 : "
  // + this.getAppDomain().getAppName() + "-" + groupNum + "-"
  // + replicaNum + ", 正在结束进程 ,请稍后刷新页面查看副本状态");
  // } else {
  // this.addErrorMessage(context, "触发停止副本"
  // + this.getAppDomain().getAppName() + "-" + groupNum + "-"
  // + replicaNum + " 失败 , 中心节点有异常");
  // }
  // }
  //
  // public void doStartOne(Context context) throws Exception {
  // int groupNum = this.getInt("groupNum");
  // int replicaNum = this.getInt("replicaNum");
  // boolean success = false;
  // try {
  // success = this.getClientProtocol().startOne(
  // this.getAppDomain().getAppName(), groupNum, replicaNum);
  // } catch (Exception e) {
  // }
  // if (success) {
  // this.addActionMessage(context, "成功触发开启副本 : "
  // + this.getAppDomain().getAppName() + "-" + groupNum + "-"
  // + replicaNum + ", 正在拉起进程 ,请稍后刷新页面查看副本状态");
  // } else {
  // this.addErrorMessage(context, "触发开启副本"
  // + this.getAppDomain().getAppName() + "-" + groupNum + "-"
  // + replicaNum + " 失败,中心节点有异常");
  // }
  // }
  //
  // public void setCoreReplication(Context context) throws Exception {
  // Integer groupNum = this.getInt("groupNum");
  // Integer numReplica = this.getInt("numReplica");
  // CoreRequest request = createIps(context, this.getServiceName(), null);
  // boolean success = false;
  // try {
  // success = this.getClientProtocol().setCoreReplication(request,
  // groupNum.shortValue(), numReplica.shortValue());
  // } catch (Exception e) {
  // }
  // if (success) {
  // this.addActionMessage(context, "成功触发 : "
  // + this.getAppDomain().getAppName() + "-" + groupNum
  // + "设置副本数 : " + numReplica);
  // } else {
  // this.addErrorMessage(context, "触发"
  // + this.getAppDomain().getAppName() + "-" + groupNum
  // + "设置副本数 : " + numReplica + " 失败 , 请先检查是否成功执行全量等");
  // }
  // }
  //
  // public void doCheckingCoreCompletion(Context context) throws Exception {
  // CheckingResponse response = this.getClientProtocol()
  // .checkingCoreCompletion(this.getServiceName());
  // if (response.isFinished()) {
  // this.addActionMessage(context, "成功创建应用 : "
  // + this.getAppDomain().getAppName() + ", 您可以在应用更新页进行操作了");
  // } else if (response.isFailed()) {
  // this.addErrorMessage(context, "创建应用 : "
  // + this.getAppDomain().getAppName() + " 失败,请联系终搜同学");
  // } else {
  // this.addErrorMessage(context, "processing");
  // }
  // }
}
