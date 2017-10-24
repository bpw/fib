package org.jikesrvm.octet;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.StringTokenizer;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.Atom;
import org.jikesrvm.util.HashSetRVM;

public class LoadDataRaces {
 
  public static void readRaces() {
    String chordDirectory = VM.staticRaceDir;
    if (VM.VerifyAssertions) { VM._assert((chordDirectory != null) && (chordDirectory.length() > 0)); }
    
    // Allocate hash set here. The VM is already supposed to be running.
    Site.raceSites = new HashSetRVM<Site>();
    
    try {
      BufferedReader reader = new BufferedReader(new FileReader(chordDirectory));
      String line = null;
      try {
        StringTokenizer st;
        while ((line = reader.readLine()) != null) {      
          if (line.length() == 0) { 
            continue;
          }
          st = new StringTokenizer(line,"|\n");
          String methodName = "";
          String methodDesc = "";
          String classDesc = "";
          String bcIndex = "";
          String lineNo = "";
          String accessType = "";
          int count = 0;
          while (st.hasMoreTokens()) {
            count++;
            String s = (String)st.nextElement();
            s = s.trim();
            String split[] = s.split(":");
            switch(count) {
            case 1:
              methodName = split[1];
              break;
            case 2:
              methodDesc = split[1];
              break;
            case 3:
              classDesc = split[1];
              break;
            case 4:
              bcIndex = split[1];
              break;
            case 5:            
              lineNo = split[1];
              break;
            case 6:
              accessType = split[1];
              break;
            default:
              VM.sysFail("Case not found while reading race file");
            }                       
          }            
          Site.registerRaceSite(Atom.findOrCreateAsciiAtom(classDesc), Atom.findOrCreateAsciiAtom(methodName), Atom.findOrCreateAsciiAtom(methodDesc), Integer.parseInt(bcIndex), Integer.parseInt(lineNo), null);          
        } 
      } catch(IOException e) {
        System.out.println(e.toString());
      } finally {
        reader.close();
      }
    } catch(IOException e) {
      VM.sysWrite("Cannot read data races from file ");
      VM.sysFail("Failed to read race file");
    }
  }  
  
}
