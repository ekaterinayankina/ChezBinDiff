This program allows you to generate a result file from the source file and the patch.
The patch stores the information for creating the result-file from the source-file.

Parameters for running the program: 
<source> <patch> <dst>

Example string to run:
java -jar ChezBinDiff\out\artifacts\ChezBinDiff\ChezBinDiff.jar <source> <patch> <dst>

Example string to updating JDK from 7.80 to 8.181:
java -jar ChezBinDiff\out\artifacts\ChezBinDiff\ChezBinDiff.jar D:\Debug\jdk-7u80-windows-x64.exe D:\Debug\NewPatch.txt D:\Debug\jdk-8u181-windows-x64.exe 
