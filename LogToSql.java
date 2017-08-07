package client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Created by guan on 2017/6/12.
 */
class LogToSql {
    private static final String mysqlBinLog = "cmd /c mysqlbinlog -v --database=";
    //客户端序列号
    private static String clientNum ;
    //二进制日志路径
    private static String logPath ;
    //转换后的SQL文件路径
    private static String sqlPath ;
    //本地数据库名
    private static String clientDB ;
    //服务端数据库名
    private static String serverDB ;
    //数据库驱动
    private static String driver;
    //数据库url
    private static String dburl;
    //数据库用户名
    private static String username;
    //数据库密码
    private static String password;
    //需要更新的表
    private static Map<String, String> tables = new HashMap<>();
    //加载日志
    private static Logger logger;

    public static void init(){
        //加载日志
        logger = Logger.getLogger("LogToSql");
        InputStream inputStream = LogToSql.class.getResourceAsStream("/logToSql.properties");
        Properties properties = new Properties();
        try {
            properties.load(inputStream);
            clientNum = properties.getProperty("clientNum");
            logPath = properties.getProperty("logPath");
            sqlPath = properties.getProperty("sqlPath");
            clientDB = properties.getProperty("clientDB");
            serverDB = properties.getProperty("serverDB");
            driver = properties.getProperty("driver");
            dburl = properties.getProperty("dburl");
            username = properties.getProperty("username");
            password = properties.getProperty("password");
            //获取需要更新的数据库表
            String table[] = properties.getProperty("tables").split(",");
            if (table.length>0){
                for (int i=0; i<table.length; i++){
                    tables.put(table[i], "valid");
                }
            }
        }
        catch (Exception e) {
            logger.warning("不能读取logToSql.properties配置文件." );
        }
    }
    public static Connection getConnection() {
        Connection conn = null;
        try {
            Class.forName(driver);
            String url = dburl+clientDB;
            String user = username;
            String pass = password;
            conn = DriverManager.getConnection(url, user, pass);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return conn;
    }

    protected String transLogToSql(int sub){
        SimpleDateFormat sdf= new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        //获取开始时间和结束时间
        Date currTime = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(currTime);
        calendar.set(Calendar.SECOND, calendar.get(Calendar.SECOND) - sub);
        String startTime = sdf.format(calendar.getTime());
        String endTime = sdf.format(currTime);
        //logger.info(startTime);
        //logger.info(endTime);
        //获取所有日志文件的路径
        File logDir = new File(logPath);
        String logFiles = null;
        if (logDir.exists()){
            File[] files = logDir.listFiles();
            for (int i=0; i<files.length; i++){
                if (logFiles==null){
                    logFiles=logPath+"/"+files[i].getName()+" ";
                }else if (!(files[i].getName().indexOf("index")!=-1)){//不考虑索引文件
                    logFiles+=logPath+"/"+files[i].getName()+" ";
                }
            }
        }
        //生成文件名
        String fileName = System.currentTimeMillis()+".sql";
        //组合成可执行的命令行
        String cmdArr = mysqlBinLog+clientDB+" "+" --start-datetime=\""+startTime+"\""+" --stop-datetime=\""+endTime+"\" "+logFiles+
                " | findstr ### ";
        try {
            Process p =Runtime.getRuntime().exec(cmdArr);
            //转换row模式下的sql为标准sql
            boolean isTrans = sqlToSql(p,fileName);
            if (isTrans) return sqlPath+fileName;
            Runtime.getRuntime().gc();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean sqlToSql(Process process, String fileName) throws IOException, SQLException {

        //所有SQL语句，最后写成文件
        String allSql = null;
        //每行数据
        String str;
        //表名和列名
        Map<String, String> tableColum = new HashMap<String, String>();
        //SQL语句，包括update、delete、insert
        String sqlUDI = null;
        //update、delete、insert等语句的开始和结束
        String flag = null;
        //查询条件主键
        String whereID = null;
        //表名
        String tableName = null;
        //列名
        List<String> columns = new LinkedList<>();
        //列名和值
        String columValue = null;
        //列的序号
        int columNu = 1;
        //SET标签
        String set = null;

        //File file = new File(sqlPath);
        //FileInputStream inputStream = new FileInputStream(rowPath+fileName);
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        Connection connection=null;
        while ((str = bufferedReader.readLine())!=null){
            //System.out.println(str);
            //解析row模式SQL
            str = str.replaceAll("="," = ");
            str = str.replaceAll("'"," ' ");
            str = str.replaceAll("`"," ` ");
            str = str.replaceAll("\\\\","\\\\\\\\");
            String[] elem = str.split(" ");
            if (connection==null){
                connection = getConnection();
            }
            //判断是否为增删改语句
            boolean isUDI = (!str.contains("SET") && !str.contains("@") && !str.contains("WHERE"));
            //获取表名和列名
            if ( isUDI &&(tableColum.get(elem[elem.length-2])==null)){
                //如果表名在待更新表里存在，则表明该表应该上传
                if (tables.get(elem[elem.length-2])!=null){
                    String sql = "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE table_name = '"+elem[elem.length-2]+"' AND table_schema = '"+clientDB+"'";
                    PreparedStatement stmt = connection.prepareStatement(sql);
                    ResultSet rs=stmt.executeQuery(sql);
                    ResultSetMetaData data=rs.getMetaData();
                    String columName = null;
                    while(rs.next()) {
                        for (int i = 1; i < data.getColumnCount()+1; i++) {
                            if (columName==null){
                                columName = rs.getString(i);
                            }else{
                                columName+=","+rs.getString(i);
                            }
                        }
                    }
                    tableColum.put(elem[elem.length-2],columName);
                }else {
                    continue;
                }
            }
            //update时
            if(isUDI &&(elem[1].equals("UPDATE")) || (flag!=null && flag.equals("UPDATE"))){
                //确定update的头语句
                if(flag==null){
                    flag = "UPDATE";
                    tableName = elem[elem.length-2];
                    sqlUDI = "UPDATE "+serverDB+"."+tableName+"";
                    String[] temp = tableColum.get(tableName).split(",");
                    for (String clName : temp){
                        columns.add(clName);
                    }
                }
                if (elem[1].equals("SET")){
                    set = "SET";
                    whereID = " WHERE ";
                }else if (elem.length>4 && (elem[3].equals("@1")) && set!=null && set.equals("SET")){
                    //判断主键是否为varchar类型
                    if (elem[5].length()==0){

                        whereID += columns.get(0)+"="+ "'"+elem[7]+clientNum+"'";
                    }else {
                        whereID += columns.get(0)+"="+elem[5]+clientNum;
                    }
                }else if (elem.length>3 && !(elem[3].equals("@1")) && set!=null && set.equals("SET")){
                    if (columValue==null){
                        if (elem.length>9){
                            //含有空格、等号或单引号的字符串类型
                            String temp = null;
                            for (int e=7; e< elem.length-1; e++ ){
                                if (temp==null){
                                    temp=elem[e];
                                }else {
                                    temp+=" "+elem[e];
                                }
                            }
                            if (temp.contains("'") || temp.contains("=")||temp.contains("`")){
                                temp = temp.replace(" ' ","\\'");
                                temp = temp.replace(" = ","=");
                                temp = temp.replace(" ` ","`");
                            }
                            columValue = columns.get(columNu)+"="+"'"+temp+"'";
                        }else if(elem.length==9){
                            //不含有空格、等号或单引号的字符串类型
                            columValue = columns.get(columNu)+"="+"'"+elem[7]+"'";
                        }else {
                            if (elem.length>6){
                                //datetime型
                                columValue = columns.get(columNu)+"="+"'"+elem[5]+" "+elem[6]+"'";
                            }else {
                                //数字型
                                columValue = columns.get(columNu)+"="+elem[5];
                            }
                        }
                        columNu++;
                    }else{
                        if (elem.length>9){
                            //含有空格、等号或单引号的字符串类型
                            String temp = null;
                            for (int e=7; e< elem.length-1; e++ ){
                                if (temp==null){

                                    temp=elem[e];
                                }else {

                                    temp+=" "+elem[e];
                                }
                            }
                            if (temp.contains("'") || temp.contains("=")||temp.contains("`")){
                                temp = temp.replace(" ' ","\\'");
                                temp = temp.replace(" = ","=");
                                temp = temp.replace(" ` ","`");
                            }

                            columValue += ","+columns.get(columNu)+"="+"'"+temp+"'";
                        }else if (elem.length==9){
                            //不含有空格、等号或单引号的字符串类型
                            columValue += ","+columns.get(columNu)+"="+"'"+elem[7]+"'";
                        }else {
                            if (elem.length>6){
                                //datetime型
                                columValue += ","+columns.get(columNu)+"="+"'"+elem[5]+" "+elem[6]+"'";
                            }else {
                                //数字型
                                columValue += ","+columns.get(columNu)+"="+elem[5];
                            }
                        }
                        columNu++;
                    }
                }
                if (set !=null){
                    if (set.equals("SET") && columNu == columns.size()){
                        sqlUDI += " SET "+columValue+" "+whereID;
                        //将update语句添加到allSql中
                        if (allSql==null){
                            allSql = sqlUDI+";\r\n";
                        }else{
                            allSql += sqlUDI+";\r\n";
                        }
                        //清空所有临时变量
                        sqlUDI = null;
                        flag = null;
                        whereID = null;
                        tableName = null;
                        columns = new LinkedList<>();
                        columValue = null;
                        columNu = 1;
                        set = null;
                        //System.out.println(allSql);
                        continue;
                    }
                }
            }
            //insert时
            if (isUDI &&(elem[1].equals("INSERT")) || (flag!=null && flag.equals("INSERT"))){
                //确定insert的头语句
                if(flag==null){
                    flag = "INSERT";
                    tableName = elem[elem.length-2];
                    sqlUDI = "INSERT INTO "+serverDB+"."+tableName+""+" VALUES(";
                    String[] temp = tableColum.get(tableName).split(",");
                    for (String clName : temp){
                        columns.add(clName);
                    }
                }
                if (elem[1].equals("SET")){
                    set = "SET";
                }else if (set!=null && set.equals("SET")){
                    if (columValue==null){
                        //判断主键是否为varchar类型
                        if (elem[5].length()==0){

                            columValue = "'"+elem[7]+clientNum+"'";
                        }else{
                            columValue = elem[5]+clientNum;
                        }
                        columNu++;
                    }else{
                        //判断是否为含有空格的数据
                        if (elem.length>9){
                            String temp = null;
                            for (int e=7; e< elem.length-1; e++ ){
                                if (temp==null ){

                                    temp=elem[e];
                                }else {

                                    temp+=" "+elem[e];
                                }
                            }
                            if (temp.contains("'") || temp.contains("=")||temp.contains("`")){
                                temp = temp.replace(" ' ","\\'");
                                temp = temp.replace(" = ","=");
                                temp = temp.replace(" ` ","`");
                            }
                            columValue += ","+"'"+temp+"'";

                        }else if(elem.length==9){
                            //不含有空格、等号或单引号的字符串类型
                            columValue += ","+"'"+elem[7]+"'";
                        }else {
                            if (elem.length>6){
                                //时间型
                                columValue += ","+"'"+elem[5]+" "+elem[6]+"'";
                            }else {
                                //数字型
                                columValue += ","+elem[5];
                            }
                        }
                        columNu++;
                    }
                }
                if (set !=null){
                    if (set.equals("SET") && columNu == columns.size()+1){
                        sqlUDI += columValue+")";
                        //将insert语句添加到allSql中
                        if (allSql==null){
                            allSql = sqlUDI+";\r\n";
                        }else{
                            allSql += sqlUDI+";\r\n";
                        }
                        //清空所有临时变量
                        sqlUDI = null;
                        flag = null;
                        tableName = null;
                        columns = new LinkedList<>();
                        columValue = null;
                        columNu = 1;
                        set = null;
                        //System.out.println(allSql);
                        continue;
                    }
                }
            }
            //delete时
            if (isUDI &&(elem[1].equals("DELETE")) || (flag!=null && flag.equals("DELETE"))){
                //确定delete的头语句
                if(flag==null){
                    flag = "DELETE";
                    tableName = elem[elem.length-2];
                    sqlUDI = "DELETE FROM "+serverDB+"."+tableName+" WHERE ";
                    String[] temp = tableColum.get(tableName).split(",");
                    for (String clName : temp){
                        columns.add(clName);
                    }
                }
                if (elem[1].equals("WHERE")){
                    set = "WHERE";
                }else if (set!=null && set.equals("WHERE")){
                    if (columValue==null){
                        //判断主键是否为varchar类型
                        if (elem[5].length()==0){

                            columValue = columns.get(columNu-1)+"="+ "'"+elem[7]+clientNum+"'";
                        }else{
                            columValue = columns.get(columNu-1)+"="+elem[5]+clientNum;
                        }
                        columNu++;
                    }
                }
                if (set !=null){
                    if (set.equals("WHERE") && columNu==2){
                        sqlUDI += columValue;
                        //将insert语句添加到allSql中
                        if (allSql==null){
                            allSql = sqlUDI+";\r\n";
                        }else{
                            allSql += sqlUDI+";\r\n";
                        }
                        //清空情况所有临时变量
                        sqlUDI = null;
                        flag = null;
                        tableName = null;
                        columns = new LinkedList<>();
                        columValue = null;
                        columNu = 1;
                        set = null;
                        //System.out.println(allSql);
                        continue;
                    }
                }
            }
        }
        bufferedReader.close();
        logger.info(allSql);
        if (allSql!=null){
            //将生成的字符串转换成SQL文件存储到本地
            OutputStreamWriter writer = null;
            try {
                writer = new OutputStreamWriter(new FileOutputStream(sqlPath+fileName),"UTF-8");
                writer.write(allSql);
                writer.flush();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                writer.close();
            }
        }
        return false;
    }


    public static void  main(String[] arg) throws IOException, SQLException {

        LogToSql logToSql = new LogToSql();
        //logToSql.sqlToSql("D:\\studysoft\\MySQL\\binlog\\s.sql");
        //logToSql.transLogToSql(30, "E:/文件传输/发送/");
        //logToSql.getFile("E:/文件传输/发送/
    }
}
