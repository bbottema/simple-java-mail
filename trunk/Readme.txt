Here are some basic instructions to build and run the test class.

With Ant (default target is 'run'):

ant clean:
- deletes the entire target folder

ant compile:
- compiles all classes to the target folder

ant run -Dhost=smtp.somehost.com -Dusername=username -Dpassword=password:
- runs the testclass with the given smtp server and account

ant jar:
- creates a library jar from the framework


-----------------------------------------------

Compiling and running the testclass from the command line:

mkdir target\classes
javac -cp lib\mailapi.jar;lib\smtp.jar;lib\log4j-1.2.15.jar;lib\activation.jar -d target\classes src\MailTest.java src\org\codemonkey\simplejavamail\*.java
java -cp lib\mailapi.jar;lib\smtp.jar;lib\log4j-1.2.15.jar;lib\activation.jar;target\classes -Dhost=smtp.someserver.com -Dusername=joe -Dpassword=sixpack  MailTest