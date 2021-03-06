package com.alibaba.rocketmq.broker.transaction.jdbc;

public class JDBCTransactionStoreConfig {
    private String jdbcDriverClass = "com.mysql.jdbc.Driver";
    private String jdbcURL = "jdbc:mysql://localhost:3306/rocketmq?useUnicode=true&characterEncoding=UTF-8";
    private String jdbcUser = "root";
    private String jdbcPassword = "password";
    private String brokerName = "Default";


    public String getJdbcDriverClass() {
        return jdbcDriverClass;
    }


    public void setJdbcDriverClass(String jdbcDriverClass) {
        this.jdbcDriverClass = jdbcDriverClass;
    }


    public String getJdbcURL() {
        return jdbcURL;
    }


    public void setJdbcURL(String jdbcURL) {
        this.jdbcURL = jdbcURL;
    }


    public String getJdbcUser() {
        return jdbcUser;
    }


    public void setJdbcUser(String jdbcUser) {
        this.jdbcUser = jdbcUser;
    }


    public String getJdbcPassword() {
        return jdbcPassword;
    }


    public void setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }

    public String getBrokerName() {
        return brokerName;
    }

    public void setBrokerName(String brokerName) {
        this.brokerName = brokerName;
    }
}
