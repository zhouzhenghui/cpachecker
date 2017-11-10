/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.util.automaton;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.CharStreams;
import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.ast.AAssignment;
import org.sosy_lab.cpachecker.cfa.ast.ABinaryExpression;
import org.sosy_lab.cpachecker.cfa.ast.AExpression;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCall;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.AIdExpression;
import org.sosy_lab.cpachecker.cfa.ast.ALeftHandSide;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CFunctionDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CTypeDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.AssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.BlankEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionSummaryEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CDeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionReturnEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionSummaryStatementEdge;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.core.CPAchecker;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.CFATraversal.CFAVisitor;
import org.sosy_lab.cpachecker.util.CFATraversal.TraversalProcess;
import org.sosy_lab.cpachecker.util.CFAUtils;
import org.sosy_lab.cpachecker.util.SpecificationProperty;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class AutomatonGraphmlCommon {

  public static final String SINK_NODE_ID = "sink";

  public static enum AssumeCase {

    THEN("condition-true"),
    ELSE("condition-false");

    private final String name;

    private AssumeCase(String pName) {
      this.name = pName;
    }

    public String getName() {
      return name;
    }

    @Override
    public String toString() {
      return getName();
    }
  }

  public static enum KeyDef {
    INVARIANT("invariant", ElementType.NODE, "invariant", "string"),
    INVARIANTSCOPE("invariant.scope", ElementType.NODE, "invariant.scope", "string"),
    NAMED("named", ElementType.NODE, "namedValue", "string"),
    LABEL("label", ElementType.NODE, "label", "string"),
    NODETYPE("nodetype", ElementType.NODE, "nodeType", "string", NodeType.ONPATH),
    ISFRONTIERNODE("frontier", ElementType.NODE, "isFrontierNode", "boolean", false),
    ISVIOLATIONNODE("violation", ElementType.NODE, "isViolationNode", "boolean", false),
    ISENTRYNODE("entry", ElementType.NODE, "isEntryNode", "boolean", false),
    ISSINKNODE("sink", ElementType.NODE, "isSinkNode", "boolean", false),
    ISCYCLEHEAD("cyclehead", ElementType.NODE, "isCycleHead", "boolean", false),
    ENTERLOOPHEAD("enterLoopHead", ElementType.EDGE, "enterLoopHead", "boolean", false),
    VIOLATEDPROPERTY("violatedProperty", ElementType.NODE, "violatedProperty", "string"),
    THREADNAME("threadName", ElementType.EDGE, "threadName", "string"),
    THREADID("threadId", ElementType.EDGE, "threadId", "string"),
    CREATETHREAD("createThread", ElementType.EDGE, "createThread", "string"),
    SOURCECODELANGUAGE("sourcecodelang", ElementType.GRAPH, "sourcecodeLanguage", "string"),
    PROGRAMFILE("programfile", ElementType.GRAPH, "programFile", "string"),
    PROGRAMHASH("programhash", ElementType.GRAPH, "programHash", "string"),
    SPECIFICATION("specification", ElementType.GRAPH, "specification", "string"),
    ARCHITECTURE("architecture", ElementType.GRAPH, "architecture", "string"),
    PRODUCER("producer", ElementType.GRAPH, "producer", "string"),
    CREATIONTIME("creationtime", ElementType.GRAPH, "creationTime", "string"),
    SOURCECODE("sourcecode", ElementType.EDGE, "sourcecode", "string"),
    STARTLINE("startline", ElementType.EDGE, "startline", "int"),
    ENDLINE("endline", ElementType.EDGE, "endline", "int"),
    OFFSET("startoffset", ElementType.EDGE, "startoffset", "int"),
    ENDOFFSET("endoffset", ElementType.EDGE, "endoffset", "int"),
    ORIGINFILE("originfile", ElementType.EDGE, "originFileName", "string"),
    LINECOLS("lineCols", ElementType.EDGE, "lineColSet", "string"),
    CONTROLCASE("control", ElementType.EDGE, "control", "string"),
    ASSUMPTION("assumption", ElementType.EDGE, "assumption", "string"),
    ASSUMPTIONRESULTFUNCTION("assumption.resultfunction", ElementType.EDGE, "assumption.resultfunction", "string"),
    ASSUMPTIONSCOPE("assumption.scope", ElementType.EDGE, "assumption.scope", "string"),
    FUNCTIONENTRY("enterFunction", ElementType.EDGE, "enterFunction", "string"),
    FUNCTIONEXIT("returnFrom", ElementType.EDGE, "returnFromFunction", "string"),
    CFAPREDECESSORNODE("predecessor", ElementType.EDGE, "predecessor", "string"),
    CFASUCCESSORNODE("successor", ElementType.EDGE, "successor", "string"),
    WITNESS_TYPE("witness-type", ElementType.GRAPH, "witness-type", "string"),
    INPUTWITNESSHASH("inputwitnesshash", ElementType.GRAPH, "inputWitnessHash", "string");

    public final String id;
    public final ElementType keyFor;
    public final String attrName;
    public final String attrType;

    /** The defaultValue is non-null, iff existent. */
    @Nullable public final String defaultValue;

    private KeyDef(String id, ElementType pKeyFor, String attrName, String attrType) {
      this(id, pKeyFor, attrName, attrType, null);
    }

    private KeyDef(String id, ElementType pKeyFor, String attrName, String attrType,
        @Nullable Object defaultValue) {
      this.id = Preconditions.checkNotNull(id);
      this.keyFor = Preconditions.checkNotNull(pKeyFor);
      this.attrName = Preconditions.checkNotNull(attrName);
      this.attrType = Preconditions.checkNotNull(attrType);
      this.defaultValue = defaultValue == null ? null : defaultValue.toString();
    }

    @Override
    public String toString() {
      return id;
    }
  }

  public static enum ElementType {
    GRAPH,
    EDGE,
    NODE;

    @Override
    public String toString() {
      return name().toLowerCase();
    }

    public static ElementType parse(String pElementType) {
      return ElementType.valueOf(pElementType.toUpperCase());
    }
  }

  public static enum NodeFlag {
    ISFRONTIER(KeyDef.ISFRONTIERNODE),
    ISVIOLATION(KeyDef.ISVIOLATIONNODE),
    ISENTRY(KeyDef.ISENTRYNODE),
    ISSINKNODE(KeyDef.ISSINKNODE),
    ISCYCLEHEAD(KeyDef.ISCYCLEHEAD);

    public final KeyDef key;

    private NodeFlag(KeyDef key) {
      this.key = key;
    }

    private final static Map<String, NodeFlag> stringToFlagMap = Maps.newHashMap();

    static {
      for (NodeFlag f : NodeFlag.values()) {
        stringToFlagMap.put(f.key.id, f);
      }
    }


    public static NodeFlag getNodeFlagByKey(final String key) {
      return stringToFlagMap.get(key);
    }
  }

  public enum WitnessType {
    VIOLATION_WITNESS("violation_witness"),
    CORRECTNESS_WITNESS("correctness_witness");

    public final String text;

    private WitnessType(String text) {
      this.text = text;
    }

    @Override
    public String toString() {
      return text;
    }

    public static Optional<WitnessType> tryParse(String pTextualRepresentation) {
      for (WitnessType element : values()) {
        if (element.text.equals(pTextualRepresentation)) {
          return Optional.of(element);
        }
      }
      if (pTextualRepresentation.equals("FALSE")) {
        return Optional.of(VIOLATION_WITNESS);
      }
      if (pTextualRepresentation.equals("TRUE")) {
        return Optional.of(CORRECTNESS_WITNESS);
      }
      if (pTextualRepresentation.equals("false_witness")) {
        return Optional.of(VIOLATION_WITNESS);
      }
      if (pTextualRepresentation.equals("true_witness")) {
        return Optional.of(CORRECTNESS_WITNESS);
      }
      return Optional.empty();
    }
  }

  public enum NodeType {
    ANNOTATION("annotation"),
    ONPATH("path");

    public final String text;

    private NodeType(String text) {
      this.text = text;
    }

    @Override
    public String toString() {
      return text;
    }

    public static NodeType fromString(String nodeTypeString) {
      return valueOf(nodeTypeString.trim().toLowerCase());
    }
  }

  public static final NodeType defaultNodeType = NodeType.ONPATH;

  public enum GraphMLTag {
    NODE("node"),
    DATA("data"),
    KEY("key"),
    GRAPH("graph"),
    DEFAULT("default"),
    EDGE("edge");

    public final String text;

    private GraphMLTag(String text) {
      this.text = text;
    }

    @Override
    public String toString() {
      return text;
    }
  }

  public static String computeHash(Path pPath) throws IOException {
    @SuppressWarnings("deprecation") // SHA1 is required by witness format
    HashCode hash = MoreFiles.asByteSource(pPath).hash(Hashing.sha1());
    return BaseEncoding.base16().lowerCase().encode(hash.asBytes());
  }

  public static class GraphMlBuilder {

    private final Document doc;
    private final Element graph;
    private final Set<KeyDef> definedKeys = EnumSet.noneOf(KeyDef.class);
    private final Map<KeyDef, Node> keyDefsToAppend = Maps.newEnumMap(KeyDef.class);

    public GraphMlBuilder(
        WitnessType pGraphType,
        @Nullable String pDefaultSourceFileName,
        CFA pCfa,
        VerificationTaskMetaData pVerificationTaskMetaData)
        throws ParserConfigurationException, DOMException, IOException {
      DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

      this.doc = docBuilder.newDocument();
      Element root = doc.createElement("graphml");
      doc.appendChild(root);
      root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
      root.setAttribute("xmlns", "http://graphml.graphdrawing.org/xmlns");

      defineKey(KeyDef.ORIGINFILE, Optional.of(pDefaultSourceFileName));
      for (KeyDef keyDef : KeyDef.values()) {
        if (keyDef.keyFor == ElementType.GRAPH) {
          defineKey(keyDef);
        }
      }

      graph = doc.createElement("graph");
      root.appendChild(graph);
      graph.setAttribute("edgedefault", "directed");
      graph.appendChild(createDataElement(KeyDef.WITNESS_TYPE, pGraphType.toString()));
      graph.appendChild(
          createDataElement(KeyDef.SOURCECODELANGUAGE, pCfa.getLanguage().toString()));
      graph.appendChild(
          createDataElement(KeyDef.PRODUCER, "CPAchecker " + CPAchecker.getCPAcheckerVersion()));

      for (SpecificationProperty property : pVerificationTaskMetaData.getProperties()) {
        graph.appendChild(createDataElement(KeyDef.SPECIFICATION, property.toString()));
      }

      for (Path specFile : pVerificationTaskMetaData.getNonPropertySpecificationFiles()) {
        graph.appendChild(
            createDataElement(
                KeyDef.SPECIFICATION,
                MoreFiles.asCharSource(specFile, Charsets.UTF_8).read().trim()));
      }
      for (Path inputWitness : pVerificationTaskMetaData.getInputWitnessFiles()) {
        graph.appendChild(createDataElement(KeyDef.INPUTWITNESSHASH, computeHash(inputWitness)));
      }

      for (Path programFile : pCfa.getFileNames()) {
        graph.appendChild(createDataElement(KeyDef.PROGRAMFILE, programFile.toString()));
      }
      for (Path programFile : pCfa.getFileNames()) {
        graph.appendChild(createDataElement(KeyDef.PROGRAMHASH, computeHash(programFile)));
      }

      graph.appendChild(
          createDataElement(KeyDef.ARCHITECTURE, getArchitecture(pCfa.getMachineModel())));
      ZonedDateTime now = ZonedDateTime.now().withNano(0);
      graph.appendChild(
          createDataElement(
              KeyDef.CREATIONTIME, now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
    }

    private void defineKey(KeyDef pKeyDef) {
      defineKey(pKeyDef, Optional.empty());
    }

    private void defineKey(KeyDef pKeyDef, Optional<String> pOverrideDefaultValue) {
      if (definedKeys.add(pKeyDef)) {
        keyDefsToAppend.put(pKeyDef,
            createKeyDefElement(pKeyDef, pOverrideDefaultValue));
      }
    }

    private Element createElement(GraphMLTag tag) {
      return doc.createElement(tag.toString());
    }

    private Element createDataElement(final KeyDef key, final String value) {
      defineKey(key);
      Element result = createElement(GraphMLTag.DATA);
      result.setAttribute("key", key.id);
      result.setTextContent(value);
      return result;
    }

    public Element createEdgeElement(final String from, final String to) {
      Element result = createElement(GraphMLTag.EDGE);
      result.setAttribute("source", from);
      result.setAttribute("target", to);
      graph.appendChild(result);
      return result;
    }

    public Element createNodeElement(String nodeId, NodeType nodeType) {
      Element result = createElement(GraphMLTag.NODE);
      result.setAttribute("id", nodeId);

      if (nodeType != defaultNodeType) {
        addDataElementChild(result, KeyDef.NODETYPE, nodeType.toString());
      }

      graph.appendChild(result);

      return result;
    }

    private Element createKeyDefElement(KeyDef pKeyDef, Optional<String> pDefaultValue) {

      Element result = createElement(GraphMLTag.KEY);

      result.setAttribute("id", pKeyDef.id);
      result.setAttribute("for", pKeyDef.keyFor.toString());
      result.setAttribute("attr.name", pKeyDef.attrName);
      result.setAttribute("attr.type", pKeyDef.attrType);

      String defaultValue = pDefaultValue.orElse(pKeyDef.defaultValue);
      if (defaultValue != null) {
        Element defaultValueElement = createElement(GraphMLTag.DEFAULT);
        defaultValueElement.setTextContent(defaultValue);
        result.appendChild(defaultValueElement);
      }

      return result;
    }

    public void addDataElementChild(Element childOf, final KeyDef key, final String value) {
      Element result = createDataElement(key, value);
      childOf.appendChild(result);
    }

    public void appendTo(Appendable pTarget) throws IOException {
      Node root = doc.getFirstChild();
      Node insertionLocation = root.getFirstChild();
      for (Node graphMLKeyDefNode : Iterables
          .consumingIterable(keyDefsToAppend.values())) {
        while (insertionLocation != null
            && insertionLocation.getNodeName().equals(GraphMLTag.KEY.toString())) {
          insertionLocation = insertionLocation.getNextSibling();
        }
        root.insertBefore(graphMLKeyDefNode, insertionLocation);
      }

      try {
        pTarget.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "1");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

        transformer.transform(new DOMSource(doc), new StreamResult(CharStreams.asWriter(pTarget)));
      } catch (TransformerException ex) {
        if (ex.getException() instanceof IOException) {
          throw (IOException) ex.getException();
        }
        throw new RuntimeException("Error while writing witness.", ex);
      }
    }

  }

  public static boolean handleAsEpsilonEdge(CFAEdge edge) {
    if (handleAsEpsilonEdge0(edge)) {
      if (edge.getSuccessor().getNumLeavingEdges() <= 0) {
        return false;
      }
      return true;
    }
    return false;
  }

  private static boolean handleAsEpsilonEdge0(CFAEdge edge) {
    if (edge instanceof BlankEdge) {
      return !(edge.getSuccessor() instanceof FunctionExitNode)
          && !isMainFunctionEntry(edge);
    } else if (edge instanceof CFunctionReturnEdge) {
      return false;
    } else if (edge instanceof CDeclarationEdge) {
      CDeclarationEdge declEdge = (CDeclarationEdge) edge;
      CDeclaration decl = declEdge.getDeclaration();
      if (decl instanceof CFunctionDeclaration) {
        return true;
      } else if (decl instanceof CTypeDeclaration) {
        return true;
      } else if (decl instanceof CVariableDeclaration) {
        CVariableDeclaration varDecl = (CVariableDeclaration) decl;
        if (varDecl.getName().toUpperCase().startsWith("__CPACHECKER_TMP")) {
          return true; // Dirty hack; would be better if these edges had no file location
        }
        CFANode successor = edge.getSuccessor();
        Iterator<CFAEdge> leavingEdges = CFAUtils.allLeavingEdges(successor).iterator();
        if (!leavingEdges.hasNext()) {
          return false;
        }
        CFAEdge successorEdge = leavingEdges.next();
        if (leavingEdges.hasNext()) {
          return false;
        }
        if (successorEdge instanceof AStatementEdge) {
          AStatementEdge statementEdge = (AStatementEdge) successorEdge;
          if (statementEdge.getFileLocation().equals(edge.getFileLocation())
              && statementEdge.getStatement() instanceof AAssignment) {
            AAssignment assignment = (AAssignment) statementEdge.getStatement();
            ALeftHandSide leftHandSide = assignment.getLeftHandSide();
            if (leftHandSide instanceof AIdExpression) {
              AIdExpression lhs = (AIdExpression) leftHandSide;
              if (lhs.getDeclaration() != null && lhs.getDeclaration().equals(varDecl)) {
                return true;
              }
            }
          }
        }
        return false;
      }
    } else if (edge instanceof CFunctionSummaryStatementEdge) {
      return true;
    }

    return false;
  }

  public static boolean isMainFunctionEntry(CFAEdge pEdge) {
    return isFunctionStartDummyEdge(pEdge)
        && !(pEdge.getPredecessor() instanceof FunctionEntryNode);
  }

  public static boolean isFunctionStartDummyEdge(CFAEdge pEdge) {
    if (!(pEdge instanceof BlankEdge)) {
      return false;
    }
    BlankEdge edge = (BlankEdge) pEdge;
    return edge.getDescription().equals("Function start dummy edge");
  }

  public static String getArchitecture(MachineModel pMachineModel) {
    final String architecture;
    switch (pMachineModel) {
      case LINUX32:
        architecture = "32bit";
        break;
      case LINUX64:
        architecture = "64bit";
        break;
      default:
        architecture = pMachineModel.toString();
        break;
    }
    return architecture;
  }

  public static Set<FileLocation> getFileLocationsFromCfaEdge(CFAEdge pEdge, FunctionEntryNode pMainEntry) {
    if (handleAsEpsilonEdge(pEdge)) {
      return Collections.emptySet();
    }
    if (isMainFunctionEntry(pEdge)) {
      FileLocation location = pMainEntry.getFileLocation();
      if (!FileLocation.DUMMY.equals(location)) {
        location = new FileLocation(
            location.getFileName(),
            location.getNiceFileName(),
            location.getNodeOffset(),
            pMainEntry.getFunctionDefinition().toString().length(),
            location.getStartingLineNumber(),
            location.getStartingLineNumber(),
            location.getStartingLineInOrigin(),
            location.getStartingLineInOrigin());
      }
      return Collections.singleton(location);
    }
    if (pEdge instanceof AStatementEdge) {
      AStatementEdge statementEdge = (AStatementEdge) pEdge;
      FileLocation statementLocation = statementEdge.getStatement().getFileLocation();
      if (!FileLocation.DUMMY.equals(statementLocation)) {
        return Collections.singleton(statementLocation);
      }
    }
    if (pEdge instanceof FunctionCallEdge) {
      FunctionCallEdge functionCallEdge = (FunctionCallEdge) pEdge;
      FunctionSummaryEdge summaryEdge = functionCallEdge.getSummaryEdge();
      if (summaryEdge != null && summaryEdge.getExpression() != null) {
        AFunctionCall call = summaryEdge.getExpression();
        if (call instanceof AFunctionCallAssignmentStatement) {
          AFunctionCallAssignmentStatement statement = (AFunctionCallAssignmentStatement) call;
          FileLocation callLocation = statement.getRightHandSide().getFileLocation();
          if (!FileLocation.DUMMY.equals(callLocation)) {
            return Collections.singleton(callLocation);
          }
        }
      }
    }
    if (pEdge instanceof AssumeEdge) {
      AssumeEdge assumeEdge = (AssumeEdge) pEdge;
      FileLocation location = assumeEdge.getFileLocation();
      if (isDefaultCase(assumeEdge)) {
        CFANode successorNode = assumeEdge.getSuccessor();
        FileLocation switchLocation = Iterables.getOnlyElement(CFAUtils.leavingEdges(successorNode)).getFileLocation();
        if (!FileLocation.DUMMY.equals(switchLocation)) {
          location = switchLocation;
        } else {
          SwitchDetector switchDetector = new SwitchDetector(assumeEdge);
          CFATraversal.dfs().backwards().traverseOnce(assumeEdge.getSuccessor(), switchDetector);
          List<FileLocation> caseLocations = FluentIterable
              .from(switchDetector.getEdgesBackwardToSwitchNode())
              .transform(e -> e.getFileLocation())
              .toList();
          location = FileLocation.merge(caseLocations);
        }

      }
      if (!FileLocation.DUMMY.equals(location)) {
        return Collections.singleton(location);
      }
    }
    return CFAUtils.getFileLocationsFromCfaEdge(pEdge);
  }

  public static Optional<FileLocation> getMinFileLocation(CFAEdge pEdge, FunctionEntryNode pMainEntry) {
    Set<FileLocation> locations = getFileLocationsFromCfaEdge(pEdge, pMainEntry);
    return getMinFileLocation(locations, (l1, l2) -> Integer.compare(l1.getNodeOffset(), l2.getNodeOffset()));
  }

  public static Optional<FileLocation> getMaxFileLocation(CFAEdge pEdge, FunctionEntryNode pMainEntry) {
    Set<FileLocation> locations = getFileLocationsFromCfaEdge(pEdge, pMainEntry);
    return getMinFileLocation(locations, (l1, l2) -> Integer.compare(l2.getNodeOffset(), l1.getNodeOffset()));
  }

  private static Optional<FileLocation> getMinFileLocation(Iterable<FileLocation> pLocations, Comparator<FileLocation> pComparator) {
    Iterator<FileLocation> locationIterator = pLocations.iterator();
    if (!locationIterator.hasNext()) {
      return Optional.empty();
    }
    FileLocation min = locationIterator.next();
    while (locationIterator.hasNext()) {
      FileLocation l = locationIterator.next();
      if (pComparator.compare(l, min) < 0) {
        min = l;
      }
    }
    return Optional.of(min);
  }

  public static boolean isPartOfSwitchStatement(AssumeEdge pAssumeEdge) {
    SwitchDetector switchDetector = new SwitchDetector(pAssumeEdge);
    CFATraversal.dfs().backwards().traverseOnce(pAssumeEdge.getSuccessor(), switchDetector);
    return switchDetector.switchDetected();
  }

  public static boolean isDefaultCase(CFAEdge pEdge) {
    if (!(pEdge instanceof AssumeEdge)) {
      return false;
    }
    AssumeEdge assumeEdge = (AssumeEdge) pEdge;
    if (assumeEdge.getTruthAssumption()) {
      return false;
    }
    FluentIterable<CFAEdge> successorEdges = CFAUtils.leavingEdges(assumeEdge.getSuccessor());
    if (successorEdges.size() != 1) {
      return false;
    }
    CFAEdge successorEdge = successorEdges.iterator().next();
    if (!(successorEdge instanceof BlankEdge)) {
      return false;
    }
    BlankEdge blankSuccessorEdge = (BlankEdge) successorEdge;
    return blankSuccessorEdge.getDescription().equals("default");
  }

  public static class SwitchDetector implements CFAVisitor {

    private final AExpression assumeExpression;

    private final AExpression switchOperand;

    private final List<AssumeEdge> edgesBackwardToSwitchNode = new ArrayList<>();

    private CFANode switchNode = null;

    public SwitchDetector(AssumeEdge pAssumeEdge) {
      assumeExpression = pAssumeEdge.getExpression();
      if (assumeExpression instanceof ABinaryExpression) {
        switchOperand = ((ABinaryExpression) assumeExpression).getOperand1();
      } else {
        switchOperand = assumeExpression;
      }
    }

    public boolean switchDetected() {
      return switchNode != null;
    }

    public List<AssumeEdge> getEdgesBackwardToSwitchNode() {
      Preconditions.checkState(switchDetected());
      return Collections.unmodifiableList(edgesBackwardToSwitchNode);
    }

    @Override
    public TraversalProcess visitEdge(CFAEdge pEdge) {
      if (switchOperand == assumeExpression) {
        return TraversalProcess.ABORT;
      }
      if (pEdge instanceof AssumeEdge) {
        AssumeEdge edge = (AssumeEdge) pEdge;
        AExpression expression = edge.getExpression();
        if (!(expression instanceof ABinaryExpression)) {
          return TraversalProcess.ABORT;
        }
        AExpression operand = ((ABinaryExpression) expression).getOperand1();
        if (!operand.equals(switchOperand)) {
          return TraversalProcess.ABORT;
        }
        edgesBackwardToSwitchNode.add(edge);
        return TraversalProcess.CONTINUE;
      } else if (pEdge instanceof BlankEdge) {
        BlankEdge edge = (BlankEdge) pEdge;
        String switchPrefix = "switch (";
        if (edge.getDescription().equals(switchPrefix + switchOperand + ")")
            && !FileLocation.DUMMY.equals(edge.getFileLocation())
            && assumeExpression.getFileLocation().getNodeOffset() == edge.getFileLocation().getNodeOffset() + switchPrefix.length()) {
          switchNode = edge.getSuccessor();
          return TraversalProcess.ABORT;
        }
        return TraversalProcess.CONTINUE;
      }
      return TraversalProcess.SKIP;
    }

    @Override
    public TraversalProcess visitNode(CFANode pNode) {
      return TraversalProcess.CONTINUE;
    }

  }

}
