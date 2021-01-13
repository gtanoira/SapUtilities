export PATH=/usr/lib/jvm/java-7-oracle/bin/:$PATH
export JAVA_HOME="/usr/lib/jvm/java-7-oracle/bin/"
export CLASSPATH="/opt/tomcat/lib/*:/opt/tomcat/webapps/ClxWebService/WEB-INF/lib/*"
echo $CLASSPATH
echo .
javac -version
echo .
javac CitiCompras.java
cp CitiCompras.class ../.
service tomcat restart
