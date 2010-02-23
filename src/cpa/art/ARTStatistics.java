package cpa.art;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.logging.Level;

import cfa.objectmodel.CFAEdge;
import cfa.objectmodel.CFANode;

import common.configuration.Configuration;
import common.configuration.Option;
import common.configuration.Options;

import cpa.common.CPAchecker;
import cpa.common.ReachedElements;
import cpa.common.interfaces.Statistics;
import cpa.symbpredabsCPA.SymbPredAbsAbstractElement;
import exceptions.InvalidConfigurationException;

@Options
public class ARTStatistics implements Statistics {

  @Option(name="ART.export")
  private boolean exportART = true;
  
  @Option(name="output.path")
  private String outputDirectory = "test/output/";

  @Option(name="ART.file")
  private String outputFile = "ART.dot";

  public ARTStatistics(Configuration config) throws InvalidConfigurationException {
    config.inject(this);
  }  
  
  @Override
  public String getName() {
    return null; // return null because we do not print statistics
  }

  @Override
  public void printStatistics(PrintWriter pOut, Result pResult,
      ReachedElements pReached) {
    if (exportART) {
      dumpARTToDotFile(pReached, new File(outputDirectory, outputFile));
    }
  }

  public static void dumpARTToDotFile(ReachedElements pReached, File outfile) {
    ARTElement firstElement = (ARTElement)pReached.getFirstElement();

    Deque<ARTElement> worklist = new LinkedList<ARTElement>();
    Set<Integer> nodesList = new HashSet<Integer>();
    Set<ARTElement> processed = new HashSet<ARTElement>();
    StringBuffer sb = new StringBuffer();
    PrintWriter out;
    try {
      out = new PrintWriter(outfile);
    } catch (FileNotFoundException e) {
      CPAchecker.logger.log(Level.WARNING,
          "Could not write ART to file ", outfile, ", (", e.getMessage(), ")");
      return;
    }
    out.println("digraph ART {");
    out.println("style=filled; color=lightgrey; ");

    worklist.add(firstElement);

    while(worklist.size() != 0){
      ARTElement currentElement = worklist.removeLast();
      if(processed.contains(currentElement)){
        continue;
      }
      processed.add(currentElement);
      if(!nodesList.contains(currentElement.getElementId())){
        String color;
        if (currentElement.isBottom()) {
          color = "black";
        } else if (currentElement.isCovered()) {
          color = "green";
        } else if (currentElement.isError()) {
          color = "red";
        } else {
          SymbPredAbsAbstractElement symbpredabselem = currentElement.retrieveWrappedElement(SymbPredAbsAbstractElement.class);
          if (symbpredabselem != null && symbpredabselem.isAbstractionNode()) {
            color = "blue";
          } else {
            color = "white";
          }
        }

        CFANode loc = currentElement.retrieveLocationElement().getLocationNode();
        String label = (loc==null ? 0 : loc.getNodeNumber()) + "000" + currentElement.getElementId();
        out.println("node [shape = diamond, color = " + color + ", style = filled, label=" + label +"] " + currentElement.getElementId() + ";");
        
        nodesList.add(currentElement.getElementId());
      }
      
      if (currentElement.isCovered()) {
        sb.append(currentElement.getElementId());
        sb.append(" -> ");
        sb.append(currentElement.getCoveredBy().getElementId());
        sb.append(" [style = dashed, label = \"covered by\"];");
      }
      
      for(ARTElement child : currentElement.getChildren()){
        CFAEdge edge = currentElement.getEdgeToChild(child);
        sb.append(currentElement.getElementId());
        sb.append(" -> ");
        sb.append(child.getElementId());
        sb.append(" [label = \"");
        sb.append(edge != null ? edge.toString().replace('"', '\'') : "");
        sb.append("\"];\n");
        if(!worklist.contains(child)){
          worklist.add(child);
        }
      }
    }

    out.println(sb.toString());
    out.println("}");
    out.flush();
    out.close();
  }
}
