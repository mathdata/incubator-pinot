/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.server.conf;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;


/**
 * The config used for Server.
 *
 *
 */
public class ServerConf {

  private static String PINOT_ = "pinot.";
  private static String PINOT_SERVER_INSTANCE = "pinot.server.instance";
  private static String PINOT_SERVER_METRICS = "pinot.server.metrics";
  private static String PINOT_SERVER_QUERY = "pinot.server.query.executor";
  private static String PINOT_SERVER_REQUEST = "pinot.server.request";
  private static String PINOT_SERVER_NETTY = "pinot.server.netty";
  private static String PINOT_SERVER_INSTANCE_DATA_MANAGER_CLASS = "pinot.server.instance.data.manager.class";
  private static String PINOT_SERVER_QUERY_EXECUTOR_CLASS = "pinot.server.query.executor.class";
  private static String PINOT_SERVER_REQUEST_HANDLER_FACTORY_CLASS = "pinot.server.requestHandlerFactory.class";

  private Configuration _serverConf;

  public ServerConf(Configuration serverConfig) {
    _serverConf = serverConfig;
  }

  public void init(Configuration serverConfig) {
    _serverConf = serverConfig;
  }

  public Configuration getInstanceDataManagerConfig() {
    return _serverConf.subset(PINOT_SERVER_INSTANCE);
  }

  public Configuration getQueryExecutorConfig() {
    return _serverConf.subset(PINOT_SERVER_QUERY);
  }

  public Configuration getRequestConfig() {
    return _serverConf.subset(PINOT_SERVER_REQUEST);
  }

  public Configuration getMetricsConfig() {
    return _serverConf.subset(PINOT_SERVER_METRICS);
  }

  public NettyServerConfig getNettyConfig() throws ConfigurationException {
    return new NettyServerConfig(_serverConf.subset(PINOT_SERVER_NETTY));
  }

  public Configuration getConfig(String component) {
    return _serverConf.subset(PINOT_ + component);
  }

  public String getInstanceDataManagerClassName() {
    return _serverConf.getString(PINOT_SERVER_INSTANCE_DATA_MANAGER_CLASS);
  }

  public String getQueryExecutorClassName() {
    return _serverConf.getString(PINOT_SERVER_QUERY_EXECUTOR_CLASS);
  }

  public String getRequestHandlerFactoryClassName() {
    return _serverConf.getString(PINOT_SERVER_REQUEST_HANDLER_FACTORY_CLASS);
  }

}
