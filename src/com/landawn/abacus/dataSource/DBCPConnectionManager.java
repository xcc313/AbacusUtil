/*
 * Copyright (c) 2015, Haiyang Li. All rights reserved.
 */

package com.landawn.abacus.dataSource;

import static com.landawn.abacus.dataSource.DataSourceConfiguration.DRIVER;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.INITIAL_SIZE;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.JNDI_NAME;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.MAX_ACTIVE;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.MAX_IDLE;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.MAX_OPEN_PREPARED_STATEMENTS_PER_CONNECTION;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.MAX_WAIT_TIME;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.MIN_IDLE;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.PASSWORD;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.TEST_ON_BORROW;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.TEST_ON_RETURN;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.URL;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.USER;
import static com.landawn.abacus.dataSource.DataSourceConfiguration.VALIDATION_QUERY;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.sql.DataSource;

import com.landawn.abacus.exception.AbacusException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;

/**
 * 
 * @since 0.8
 * 
 * @author Haiyang Li
 */
class DBCPConnectionManager extends AbstractConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(DBCPConnectionManager.class);

    private final DataSource ds;
    private final org.apache.commons.dbcp.BasicDataSource bds;

    public DBCPConnectionManager(Map<String, ?> props) {
        super(props);

        if (properties.containsKey(JNDI_NAME)) {
            ds = createJNDIDataSource(properties);
            bds = null;
        } else {
            try {
                bds = (org.apache.commons.dbcp.BasicDataSource) org.apache.commons.dbcp.BasicDataSourceFactory.createDataSource(new Properties());
            } catch (Exception e) {
                String msg = AbacusException.getErrorMsg(e);
                logger.warn(msg);
                throw new RuntimeException(msg, e);
            }

            bds.setDriverClassName(properties.get(DRIVER));
            bds.setUrl(properties.get(URL));
            bds.setUsername(properties.get(USER));
            bds.setPassword(properties.get(PASSWORD));

            bds.setInitialSize(Integer.valueOf(properties.get(INITIAL_SIZE)));
            bds.setMinIdle(Integer.valueOf(properties.get(MIN_IDLE)));
            bds.setMaxIdle(Integer.valueOf(properties.get(MAX_IDLE)));
            bds.setMaxActive(Integer.valueOf(properties.get(MAX_ACTIVE)));
            bds.setMaxOpenPreparedStatements(Integer.valueOf(properties.get(MAX_OPEN_PREPARED_STATEMENTS_PER_CONNECTION)));
            bds.setMaxWait(Long.valueOf(properties.get(MAX_WAIT_TIME)));
            bds.setTestOnBorrow(Boolean.valueOf(properties.get(TEST_ON_BORROW)));
            bds.setTestOnReturn(Boolean.valueOf(properties.get(TEST_ON_RETURN)));
            bds.setValidationQuery(properties.get(VALIDATION_QUERY));

            String st = "";
            Set<Object> propNames = connectionProperties.keySet();

            for (Object propName : propNames) {
                st += ((propName + "=" + connectionProperties.getProperty(propName.toString())) + ";");
            }

            bds.setConnectionProperties(st);
            ds = bds;
        }
    }

    @Override
    public int getMaxActive() {
        return bds.getMaxActive();
    }

    @Override
    public int getNumActive() {
        return bds.getNumActive();
    }

    @Override
    public Connection getConnection() {
        try {
            return ds.getConnection();
        } catch (SQLException e) {
            String msg = AbacusException.getErrorMsg(e);
            logger.warn(msg);
            throw new UncheckedSQLException(msg, e);
        }
    }

    @Override
    public void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                String msg = AbacusException.getErrorMsg(e);
                logger.warn(msg);
                throw new UncheckedSQLException(msg, e);
            }
        }
    }

    @Override
    public void detroyConnection(Connection conn) {
        closeConnection(conn);
    }

    @Override
    public void close() {
        if (bds != null) {
            try {
                bds.close();
            } catch (SQLException e) {
                String msg = AbacusException.getErrorMsg(e);
                logger.warn(msg);
                throw new UncheckedSQLException(msg, e);
            }
        }
    }
}
