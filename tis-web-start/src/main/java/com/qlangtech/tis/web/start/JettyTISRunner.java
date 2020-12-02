/**
 * Copyright (c) 2020 QingLang, Inc. <baisui@qlangtech.com>
 *
 * This program is free software: you can use, redistribute, and/or modify
 * it under the terms of the GNU Affero General Public License, version 3
 * or later ("AGPL"), as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.qlangtech.tis.web.start;

import com.qlangtech.tis.health.check.IStatusChecker;
import com.qlangtech.tis.health.check.StatusLevel;
import com.qlangtech.tis.health.check.StatusModel;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 百岁（baisui@qlangtech.com）
 * @date 2019年1月17日
 */
public class JettyTISRunner {

    private Server server;

    // FilterHolder dispatchFilter;
    // String context;
    private static JettyTISRunner jetty;

    private static final Logger logger = LoggerFactory.getLogger(JettyTISRunner.class);

    public static void start(String contextPath, int port) throws Exception {
        start(contextPath, port, (c) -> {
        });
    }

    /**
     * A main class that starts jetty+solr This is useful for debugging
     */
    public static void start(String contextPath, int port, IWebAppContextSetter contextSetter) throws Exception {
        if (jetty != null) {
            throw new IllegalStateException("instance jetty shall be null");
        }
        jetty = new JettyTISRunner(contextPath, port, contextSetter);
        jetty.start();
    }

    /**
     * 关闭jetty
     *
     * @throws Exception
     */
    public static void stopJetty() throws Exception {
        if (jetty == null) {
            throw new IllegalStateException("instance jetty have not been initialize");
        }
        jetty.stop();
    }

    private final int port;

    private final IWebAppContextSetter contextSetter;

    private HandlerList handlers = new HandlerList();

    private final AtomicInteger contextAddCount = new AtomicInteger();

    JettyTISRunner(String context, int port, IWebAppContextSetter contextSetter) throws Exception {
        this(port, contextSetter);
        this.addContext(context, new File("."), false);
    }

    JettyTISRunner(int port, IWebAppContextSetter contextSetter) {
        this.port = port;
        this.contextSetter = contextSetter;
    }

    /**
     * 启动子应用
     *
     * @param contextDir
     * @throws Exception
     */
    public void addContext(File contextDir) throws Exception {
        this.addContext("/" + contextDir.getName(), contextDir, true);
    }

    public void addContext(final String context, File contextDir, boolean addDirJars) throws Exception {
        final File webappDir = getWebapp(contextDir);
        if (!(webappDir.exists() && webappDir.isDirectory() && (new File(webappDir, TisApp.PATH_WEB_XML)).exists())) {
            logger.warn("dir is not webapp,skip:{}", webappDir.getAbsolutePath());
            return;
        }
        Resource webContentResource = Resource.newResource(webappDir);
        WebAppContext webAppContext = new WebAppContext(webContentResource, context);
        if (addDirJars) {
            final File libsDir = new File(contextDir, "lib");
            if (!(libsDir.exists() && libsDir.isDirectory())) {
                throw new IllegalStateException("libs is illegal:" + libsDir.getAbsolutePath());
            }
            List<URL> jarfiles = new ArrayList<>();
            for (String path : libsDir.list()) {
                jarfiles.add((new File(libsDir, path)).toURI().toURL());
            }
            // contextCloassLoader.addJars(Resource.newResource(libsDir));
            File confDir = new File(contextDir, "conf");
            if (!confDir.exists()) {
                throw new IllegalStateException("web context:" + context + " dir not exist:" + confDir.getAbsolutePath());
            }
            // contextCloassLoader.addClassPath(Resource.newResource(confDir));
            jarfiles.add(confDir.toURI().toURL());
            TISAppClassLoader contextCloassLoader = new TISAppClassLoader(Thread.currentThread().getContextClassLoader(), jarfiles.toArray(new URL[jarfiles.size()]));
            webAppContext.setClassLoader(contextCloassLoader);
        } else {
            webAppContext.setClassLoader(this.getClass().getClassLoader());
        }
        webAppContext.setDescriptor("/" + TisApp.PATH_WEB_XML);
        webAppContext.setDisplayName(context);
        webAppContext.setConfigurationDiscovered(true);
        webAppContext.setParentLoaderPriority(true);
        webAppContext.setThrowUnavailableOnStartupException(true);
        webAppContext.addServlet(CheckHealth.class, "/check_health");
        contextSetter.process(webAppContext);
        handlers.addHandler(webAppContext);
        contextAddCount.incrementAndGet();
    }

    public File getWebapp(File contextDir) {
        return new File(contextDir, "webapp");
    }

    private void init() {
        if (contextAddCount.get() < 1 || this.handlers.getHandlers().length < 1) {
            throw new IllegalStateException("handlers can not small than 1");
        }
        server = new Server(new QueuedThreadPool(450));
        // << 启用jndi
        org.eclipse.jetty.webapp.Configuration.ClassList classlist
                = org.eclipse.jetty.webapp.Configuration.ClassList.setServerDefault(server);
        classlist.addAfter("org.eclipse.jetty.webapp.FragmentConfiguration"
                , "org.eclipse.jetty.plus.webapp.EnvConfiguration"
                , "org.eclipse.jetty.plus.webapp.PlusConfiguration");
        // >>
        NetworkTrafficServerConnector connector = new NetworkTrafficServerConnector(server);
        HttpConfiguration configuration = new HttpConfiguration();
        connector.addConnectionFactory(new HTTP2CServerConnectionFactory(configuration));
        connector.setPort(port);
        server.setConnectors(new Connector[] { connector });
        server.setStopAtShutdown(true);
        server.setHandler(handlers);
    }

    public interface IWebAppContextSetter {

        void process(WebAppContext context);
    }

    public static class CheckHealth extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private List<IStatusChecker> checks;

        @Override
        public void init() throws ServletException {
            this.checks = new ArrayList<>();
            ServiceLoader.load(IStatusChecker.class).forEach((r) -> {
                if (r instanceof IServletContextAware) {
                    ((IServletContextAware) r).setServletContext(getServletContext());
                }
                checks.add(r);
            });
        }

        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
            for (IStatusChecker check : checks) {
                StatusModel model = check.check();
                if (model.level != StatusLevel.OK) {
                    res.getWriter().print("Check[" + check.getClass() + "] fail:" + model.message);
                    return;
                }
            }
            res.getWriter().write("ok");
        }
    }

    public void addServlet(HttpServlet servlet, String pathSpec) {
    // this.rootContext.addServlet(new ServletHolder(servlet), pathSpec);
    }

    public void addFilter(FilterHolder filter, String urlpattern) {
    // this.rootContext.addFilter(filter, urlpattern,
    // EnumSet.of(DispatcherType.REQUEST));
    // FilterRegistrationBean registrationBean = new
    // FilterRegistrationBean();
    // registrationBean.setFilter(new TisSolrDispatchFilter());
    // registrationBean.addUrlPatterns("/*");
    // registrationBean.addInitParameter("excludePatterns",
    // "/css/.+,/js/.+,/img/.+,/tpl/.+");
    // registrationBean.setName("SolrRequestFilter");
    // registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    // return registrationBean;
    }

    // public static class InnerFilter implements Filter {
    // 
    // @Override
    // public void init(FilterConfig filterConfig) throws ServletException {
    // }
    // 
    // @Override
    // public void doFilter(ServletRequest request, ServletResponse response,
    // FilterChain chain)
    // throws IOException, ServletException {
    // chain.doFilter(request, response);
    // }
    // 
    // @Override
    // public void destroy() {
    // }
    // }
    // ------------------------------------------------------------------------------------------------
    // ------------------------------------------------------------------------------------------------
    public void start() throws Exception {
        start(true);
    }

    private void start(boolean waitForSolr) throws Exception {
        this.init();
        if (!server.isRunning()) {
            server.start();
            server.join();
        }
    // if (waitForSolr)
    // waitForSolr(context);
    }

    private void stop() throws Exception {
        if (server.isRunning()) {
            server.stop();
        // server.join();
        }
    }

    /**
     * This is a stupid hack to give jetty something to attach to
     */
    public static class Servlet404 extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.sendError(404, "Can not find: " + req.getRequestURI());
        }
    }
}
