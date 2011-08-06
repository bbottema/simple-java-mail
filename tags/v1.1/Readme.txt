Here are some basic instructions to build and run the test class.

With Ant (default target is run):

ant clean:
- deletes the entire target folder

ant compile:
- compiles all classes to the target folder

ant run -Dhost=smtp.somehost.com -Dusername=username -Dpassword=password:
- runs the testclass with the given smtp server and account

ant jar:
- creates a library jar from the framework