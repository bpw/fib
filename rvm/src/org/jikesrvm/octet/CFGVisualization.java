package org.jikesrvm.octet;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Enumeration;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.opt.ir.BasicBlock;
import org.jikesrvm.compilers.opt.ir.Call;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.Instruction;

/**
 * 
 * @author aritra
 *
 */
// SB: We could do some potential enhancements, like deleting all existing files before 
// generating new files. It should help managing files getting generated. Then, creating/deleting
// the directory depending on whether it exists/or not.
public class CFGVisualization {

  BufferedWriter out = null;
  static int countCfg = 1;

  public CFGVisualization(IR ir, String prefix) {
    try {
      File dir;
      if (VM.octetIODir != null) {
        dir = new File(VM.octetIODir);
      } else {
        dir = new File(System.getProperty("user.home"), "/CFGS");
      }
      RVMMethod method = ir.getMethod();
      String fileName = prefix + (countCfg++) + "_" +
                        method.getDeclaringClass().getDescriptor().classNameFromDescriptor() + "_" +
                        method.getName() + "_" +
                        /*method.getSignature() + "_" + */
                        "opt" + ir.options.getOptLevel() + ".graph";
      FileWriter fstream = new FileWriter(new File(dir, fileName));
      out = new BufferedWriter(fstream);
    } catch (IOException io) {
      System.out.println(io);
    }
  }
  /** method to set color for headers in sce analysis **/
  public static void setColorForBasicBlocks(BasicBlock bb) {

    if(bb !=  null) {
      bb.setScratch(-101);
    }
  }
  public boolean isOctetBarrier(Instruction inst) {
    boolean returnVar = false;
    if (Call.conforms(inst)) {
      RVMClass classBarrier;
      if (Call.getMethod(inst).hasTarget()) {
        classBarrier = Call.getMethod(inst).getTarget().getDeclaringClass();
        if (classBarrier.getTypeRef() == TypeReference.OctetBarriers) {
          returnVar = true;
        }
      }
    }
    return returnVar; 
  }

  /** format Call instructions */
  protected String handleCalls(Instruction inst) { 
    String s = "";

    if (Call.getMethod(inst).hasTarget()) { 
      s += Call.getMethod(inst).getTarget().getDeclaringClass() + ":";
      s += Call.getMethod(inst).getTarget().getName() + ":";
      s += Call.getMethod(inst).getTarget().getDescriptor() + ":";
      int params = Call.getNumberOfParams(inst);
      for (int i = 1; i <= params; i++) {
        String comma = "";
        if(i < params) {
          comma = ", ";
        } else {
          comma ="; ";
        }
        s += Call.getParam(inst, i-1).toString() + comma;
      }
    }  
    return s;
  }

  /** Format Octet barriers and calls */
  protected String shrinkInstruction(Instruction inst) {
    if (isOctetBarrier(inst)) {
      //String s=inst.toString().substring(44, inst.toString().length());
      String s = "OctetBarrier " + handleCalls(inst);
      return s;
    } else if (Call.conforms(inst)) {   
      String s = "CALL " + handleCalls(inst);
      return s;
    } else {
      return inst.toString();
    }
  }
  /** Reset scratch objects of basic blocks used in DFS */
  protected void resetScratchObjects(IR ir) {
    BasicBlock bb;
    for (Enumeration<BasicBlock> e = ir.getBasicBlocks(); e.hasMoreElements();) {
      bb = e.nextElement();
      bb.scratchObject = "0";
    }
  }

  /** Print entry block and call dfsCFG for the rest of the graph */
  public void visualizeCFG(IR ir) {
    //BasicBlockEnumeration blockEnumeration = ir.getBasicBlocks();
    String color = "";
    resetScratchObjects(ir);
    String entryLabel = "";
    try {
      writeCFG("digraph G {\n node [shape=box];\n");
      entryLabel += "ENTRY" + "[ label=\"" + "ENTRY" + "\\n";
      entryLabel += enumerateAndFormatInstructions(ir.cfg.entry());
      entryLabel += "\"" + color + "];\n";
      writeCFG(entryLabel);
      dfsCFG(ir.cfg.entry(), ir);
      writeCFG("}");
      out.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
  
  protected void writeCFG(String str) {
    try {
      out.write(str);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** print formatted instructions in a basic block */
  protected String enumerateAndFormatInstructions(BasicBlock succBB) {
    Instruction inst;
    String next = "";
    for (Enumeration<Instruction> e = succBB.forwardInstrEnumerator(); e.hasMoreElements();) {
      inst = e.nextElement();
      int sc1 = 0;
      String s=shrinkInstruction(inst);
      s = s.replaceAll("\n", " ");
      s = s.replaceAll("\"", "\\\\\"");
      if (inst.position!=null) {  
        sc1 = inst.position.getMethod().getLineNumberForBCIndex(inst.getBytecodeIndex());
      }
      next += s + " " + ((Integer)inst.getBytecodeIndex()).toString() + "," + ((Integer)sc1).toString() + "\\n";
    }
    return next;
  }

  /** Generate control-flow edges for basic blocks */
  protected WrapperString setDirectionalEdges(BasicBlock succBB, IR ir, BasicBlock bb, WrapperString obj) {
    if (bb == ir.cfg.entry() || succBB.isExit()) {
      if (bb == ir.cfg.entry() && (!succBB.isExit())) {  
        obj.setTo("BB"+succBB.getNumber());  
        obj.setStr("ENTRY" + "->" + "{"+ obj.getTo() + "}" + ";\n"); 
      } else if (!(bb == ir.cfg.entry()) && (succBB.isExit())) {  
        obj.setTo("EXIT");
        obj.setStr("BB" + bb.getNumber() + "->" + "{" + obj.getTo() + "}" + ";\n");
      }
    } else { 
      obj.setTo("BB"+succBB.getNumber());
      if (succBB == ir.cfg.entry()) {
        obj.setTo("ENTRY");
      }
      obj.setStr("BB" + bb.getNumber() + "->" + "{" + obj.getTo() + "}" + ";\n");
    }
    return obj;
  }

  /** Wrapper for a method returning two strings*/
  public class WrapperString {
    String to = "";
    String str = "";
    public String getTo() {
      return to;
    }
    public void setTo(String to) {
      this.to = to;
    }
    public String getStr() {
      return str;
    }
    public void setStr(String str) {
      this.str = str;
    }
  }

  /** DFS to print the entire CFG */
  protected void dfsCFG(BasicBlock bb, IR ir) {
    bb.scratchObject = "1";
    String color = "";
    Enumeration<BasicBlock> successors = bb.getOutNodes();
    while (successors.hasMoreElements()) {
      String str = "";
      String to = "";
      String next = "";
      BasicBlock succBB = successors.nextElement();
      WrapperString obj = new WrapperString();
      WrapperString returnObj = setDirectionalEdges(succBB, ir, bb, obj);
      to = returnObj.getTo();
      str = returnObj.getStr();
      try {
        if (!("".equalsIgnoreCase(to))) {
          next += to + "[ label=\"" + to + "\\n";
        }
        next += enumerateAndFormatInstructions(succBB);
        if (!("".equalsIgnoreCase(to))) {
          next += "\"" + color + "];\n";
        }
        writeCFG(str);
        writeCFG(next);
      } catch (Exception e) {
        e.printStackTrace();
      }
      if (succBB.scratchObject instanceof String) { 
        if (((String)succBB.scratchObject).equalsIgnoreCase("0")) {
          dfsCFG(succBB,ir);
        }
      }
    }
    bb.scratchObject = "2";
  }

}

