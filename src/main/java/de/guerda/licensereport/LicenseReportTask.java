package de.guerda.licensereport;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Iterator;
import java.util.Vector;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * @author Philip Gillißen
 * 
 */
public final class LicenseReportTask extends Task {

  Vector<FileSet> fileSets;
  private static final Pattern LICENSE_PATTERN = Pattern.compile("^Bundle-License: (.*)$");
  private Document document;
  private File resultFile;
  private Element librariesElement;

  public LicenseReportTask() {
    fileSets = new Vector<>();
  }

  @Override
  public void execute() throws BuildException {
    initialize();
    validate();

    for (Iterator<FileSet> tmpIterator = fileSets.iterator(); tmpIterator.hasNext();) { // 2
      FileSet tempFileSet = tmpIterator.next();
      DirectoryScanner tempScanner = tempFileSet.getDirectoryScanner(getProject()); // 3
      String[] tempFiles = tempScanner.getIncludedFiles();
      for (String tmpFileName : tempFiles) {
        inspectJar(tempFileSet.getDir(), tmpFileName);
      }
    }
    createResultXml();
  }

  private void createResultXml() {
    try {
      TransformerFactory tmpTransformerFactory = TransformerFactory.newInstance();
      Transformer tmpTransformer;
      tmpTransformer = tmpTransformerFactory.newTransformer();
      tmpTransformer.setOutputProperty(OutputKeys.INDENT, "yes");
      tmpTransformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

      DOMSource tmpSource = new DOMSource(document);
      StreamResult tmpResult = new StreamResult(resultFile);

      tmpTransformer.transform(tmpSource, tmpResult);
    } catch (TransformerException e) {
      throw new BuildException("Could not create XML file '" + resultFile.getAbsolutePath() + "'!", e);
    }
  }

  private void initialize() {
    try {
      DocumentBuilderFactory tmpFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder tmpBuilder = tmpFactory.newDocumentBuilder();
      document = tmpBuilder.newDocument();
      Element rootElement = document.createElement("license-report");
      document.appendChild(rootElement);
      librariesElement = document.createElement("libraries");
      rootElement.appendChild(librariesElement);

      resultFile = File.createTempFile("license-report-results_", ".xml");
      System.out.println(resultFile.getAbsolutePath());
    } catch (IOException | ParserConfigurationException e) {
      throw new BuildException("Could not create license report results file", e);
    }
  }

  private void inspectJar(File aDir, String aString) {
    File tmpFile = new File(aDir, aString);
    if (!tmpFile.exists() || !tmpFile.canRead()) {
      throw new BuildException("File not found or not readable: " + aDir.getAbsolutePath() + aString);
    }
    StringBuffer tmpLicense = new StringBuffer();
    String tmpLicenseHead;
    Vector<String> tmpLines;

    // Search MANIFEST.MF for license information
    String tmpManifestFilename = "META-INF/MANIFEST.MF";
    tmpLines = findAndReadFileFromJar(tmpFile, tmpManifestFilename);

    for (String tmpLine : tmpLines) {
      Matcher tmpMatcher = LICENSE_PATTERN.matcher(tmpLine);
      if (tmpMatcher.matches()) {
        tmpLicense.append(tmpManifestFilename + ": " + tmpMatcher.group(1));
        break;
      }
    }

    // Search for META-INF/LICENSE.txt
    tmpLicenseHead = findFileAndExtractHeaderFromJar(tmpFile, "META-INF/LICENSE.txt");
    if (!isBlank(tmpLicenseHead)) {
      tmpLicense.append(tmpLicenseHead);
    }

    // Search for META-INF/LICENSE
    tmpLicenseHead = findFileAndExtractHeaderFromJar(tmpFile, "META-INF/LICENSE");
    if (!isBlank(tmpLicenseHead)) {
      tmpLicense.append(tmpLicenseHead);
    }

    // Search for LICENSE
    tmpLicenseHead = findFileAndExtractHeaderFromJar(tmpFile, "LICENSE");
    if (!isBlank(tmpLicenseHead)) {
      tmpLicense.append(tmpLicenseHead);
    }

    // Search for LICENSE.txt
    tmpLicenseHead = findFileAndExtractHeaderFromJar(tmpFile, "LICENSE.txt");
    if (!isBlank(tmpLicenseHead)) {
      tmpLicense.append(tmpLicenseHead);
    }

    // Search for LICENSE
    tmpLicenseHead = findFileAndExtractHeaderFromJar(tmpFile, "license/LICENSE.txt");
    if (!isBlank(tmpLicenseHead)) {
      tmpLicense.append(tmpLicenseHead);
    }

    if (tmpLicense.length() == 0) {
      System.out.printf("%50s\t%s\r\n", tmpFile.getName(), "No License Information Found");
      addResultToReport(tmpFile.getName(), "No License Information Found");
    } else {
      System.out.printf("%50s\t%s\r\n", tmpFile.getName(), tmpLicense);
      addResultToReport(tmpFile.getName(), tmpLicense.toString());
    }

  }

  private void addResultToReport(String aName, String aString) {
    Node tmpChild = document.createElement("library");
    Element tmpNameElement = document.createElement("name");
    tmpNameElement.setTextContent(aName);
    tmpChild.appendChild(tmpNameElement);

    Element tmpLicenseElement = document.createElement("license");
    CDATASection tmpCDATASection = document.createCDATASection(aString);
    tmpLicenseElement.appendChild(tmpCDATASection);
    tmpChild.appendChild(tmpLicenseElement);

    librariesElement.appendChild(tmpChild);
  }

  private boolean isBlank(String aString) {
    int tmpLength;
    if (aString == null || (tmpLength = aString.length()) == 0) {
      return true;
    }
    for (int i = 0; i < tmpLength; i++) {
      if ((Character.isWhitespace(aString.charAt(i)) == false)) {
        return false;
      }
    }
    return true;
  }

  private String findFileAndExtractHeaderFromJar(File aFile, String aLicenseFilename) {
    StringBuffer tmpLicenseHead = new StringBuffer();
    Vector<String> tmpLines;
    tmpLines = findAndReadFileFromJar(aFile, aLicenseFilename);
    if (tmpLines.size() > 0) {
      for (int i = 0; i < tmpLines.size() && i <= 5; i++) {
        tmpLicenseHead.append(tmpLines.get(i).trim() + " ");
      }
    }
    if (tmpLicenseHead.toString().length() > 0) {
      return aLicenseFilename + ": " + tmpLicenseHead.toString();
    } else {
      return null;
    }
  }

  private Vector<String> findAndReadFileFromJar(File tmpFile, String tmpManifestFilename) {
    Vector<String> tmpResult = new Vector<>();
    String tmpJarFilePrefix = "jar:file:" + tmpFile.getAbsolutePath() + "!/";
    JarFile tmpJarFile = null;
    try {
      tmpJarFile = new JarFile(tmpFile);
      if (tmpJarFile.getJarEntry(tmpManifestFilename) != null) {
        try {
          URL url = new URL(tmpJarFilePrefix + tmpManifestFilename);
          InputStream tmpInputStream = url.openStream();
          InputStreamReader tmpInputStreamReader = new InputStreamReader(tmpInputStream);
          BufferedReader tmpBufferedReader = new BufferedReader(tmpInputStreamReader);
          String tmpData = null;
          while ((tmpData = tmpBufferedReader.readLine()) != null) {
            tmpResult.add(tmpData);
          }
        } catch (IOException e) {
          throw new BuildException("Could not read JAR file '" + tmpFile.getAbsolutePath() + "'!", e);
        }
      }
    } catch (IOException e1) {
      throw new BuildException("Could not read JAR file '" + tmpFile.getAbsolutePath() + "'!", e1);
    } finally {
      try {
        if (tmpJarFile != null) {
          tmpJarFile.close();
        }
      } catch (IOException e) {
      }
    }
    return tmpResult;
  }

  private void validate() throws BuildException {
    if (fileSets.size() == 0) {
      throw new BuildException("No Files given");
    }
    System.out.println(fileSets.size() + " given filesets.");
  }

  // getter setter

  public void addFileset(FileSet fileset) {
    fileSets.add(fileset);
  }

}
