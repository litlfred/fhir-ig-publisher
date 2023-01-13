package org.hl7.fhir.igtools.publisher;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.conformance.profile.ProfileKnowledgeProvider;
import org.hl7.fhir.igtools.publisher.IGKnowledgeProvider;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.context.IWorkerContext.ILoggingService.LogCategory;
import org.hl7.fhir.r5.model.ElementDefinition;
import org.hl7.fhir.r5.model.IdType;
import org.hl7.fhir.r5.model.StringType;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.StructureDefinition.StructureDefinitionSnapshotComponent;
import org.hl7.fhir.r5.model.StructureMap;
import org.hl7.fhir.r5.model.StructureMap;
import org.hl7.fhir.r5.model.StructureMap.StructureMapGroupComponent;
import org.hl7.fhir.r5.model.StructureMap.StructureMapGroupInputComponent;
import org.hl7.fhir.r5.model.StructureMap.StructureMapGroupRuleComponent;
import org.hl7.fhir.r5.model.StructureMap.StructureMapGroupRuleDependentComponent;
import org.hl7.fhir.r5.model.StructureMap.StructureMapGroupRuleSourceComponent;
import org.hl7.fhir.r5.model.StructureMap.StructureMapGroupRuleTargetComponent;
import org.hl7.fhir.r5.model.StructureMap.StructureMapGroupRuleTargetParameterComponent;
import org.hl7.fhir.r5.model.StructureMap.StructureMapInputMode;
import org.hl7.fhir.r5.model.StructureMap.StructureMapModelMode;
import org.hl7.fhir.r5.model.StructureMap.StructureMapStructureComponent;
import org.hl7.fhir.r5.model.StructureMap.StructureMapTransform;
import org.hl7.fhir.r5.utils.structuremap.ITransformerServices;
import org.hl7.fhir.r5.utils.structuremap.StructureMapUtilities;


import org.hl7.fhir.utilities.TextFile;



/*-
 * #%L
 * org.hl7.fhir.publisher.core
 * %%
 * Copyright (C) 2014 - 2019 Health Level 7
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

/**
 * render UML diagrams for structure map
 */


public class UMLStructureMapUtilities extends StructureMapUtilities {


    
    private final IWorkerContext worker; //this should have been a protected variable in parent class :-(

    public UMLStructureMapUtilities(IWorkerContext worker, ITransformerServices services, ProfileKnowledgeProvider pkp) {
	super(worker,services,pkp);
	this.worker = worker;
    }

    public UMLStructureMapUtilities(IWorkerContext worker, ITransformerServices services) {
	super(worker,services);
	this.worker = worker;
    }

    public UMLStructureMapUtilities(IWorkerContext worker) {
	super(worker);
	this.worker = worker;
    }

    
    private void logDebugMessage(IWorkerContext.ILoggingService.LogCategory category, String message) {
	worker.getLogger().logDebugMessage(category,message);
    }
    private void log(String s) {
	worker.getLogger().logMessage(s);
    }


    // String umlFilename =  mapName + ".uml";
    // File umlFile = new File(Utilities.path(outputDir.getAbsolutePath(),umlFilename));
    // String umlSource = renderStructureMapAsUML(map);
    // TextFile.stringToFile(umlSource, umlFile);
    
    //this UML rendering code doesn't belong here really.


    Map<String,String> svars;
    Map<String,String> tvars;
    protected Map<String,List<String>> dependents = new HashMap<String,List<String>>(); //too lazy to do this in the constructors, i suppose
    //              rule      source/target
    protected Map<String,Map<String,List<String>>> arrows = new HashMap<String,Map<String,List<String>>>();
    //             rule      source   target

    protected void reset() {
	svars = new TreeMap<String,String>();
	tvars = new TreeMap<String,String>();
	dependents = new HashMap<String,List<String>>();
	arrows = new HashMap<String,Map<String,List<String>>>();
    }
    
    public Map<String,String> renderUML(StructureMap map) throws FHIRException  {
	reset();
	StructureDefinition source = null;
	StructureDefinition target = null;
	StructureMapGroupComponent group = map.getGroupFirstRep();
	log("looking for inital source and target vartiables in group " + group.getName());

	for (StructureMapStructureComponent structure : map.getStructure()){
	    log(" Checking structure with alias=(" + structure.getAlias() + ") and url=(" + structure.getUrl() + ")");
	    switch (structure.getMode()) {
	    case TARGET :
		if ( (target = (StructureDefinition) worker.fetchResource(StructureDefinition.class, structure.getUrl())) == null) {
		    continue;
		}
		log("  Target SD has name " + target.getName() + " and type " + target.getType());		
		for ( StructureMapGroupInputComponent input : group.getInput())  {
		    log("    Scanninng input (" + input.getName() + ") with type <" + input.getType() + ">") ;
		    if ( input.getMode() != StructureMapInputMode.TARGET
			 || ((structure.hasAlias() && ! structure.getAlias().equals(input.getType()))
			&& ! target.getType().equals(input.getType()))
			) {
			continue;
		    }
		    log("      Adding to target vars:" + target.getName()  );
		    //tvars.put(input.getName(),target.getName()); //or .getType() or getTypeName()?
		    tvars.put(input.getName(),"."); //or .getType() or getTypeName()?
		    addTPort(".");
		}
		break;
	    case SOURCE :
		if ( (source = (StructureDefinition) worker.fetchResource(StructureDefinition.class, structure.getUrl())) == null) {
		    continue;
		}
		log("  Source SD has name " + source.getName() + " and type " + source.getType());
		for ( StructureMapGroupInputComponent input : group.getInput())  {
		    log("    Scanninng input (" + input.getName() + ") with type <" + input.getType() + ">") ;
		    if ( input.getMode() != StructureMapInputMode.SOURCE
			 || ((structure.hasAlias() && ! structure.getAlias().equals(input.getType()))
			     && ! source.getType().equals(input.getType()))
			) {
			continue;
		    }
		    log("      Adding to source vars:" + source.getName() );
		    //svars.put(input.getName(),source.getName()); //or .getType() or getTypeName()?
		    svars.put(input.getName(),"."); //or .getType() or getTypeName()?
		    addSPort(".");
		}
		break;
	    }      
	}
	log("done matching first group against structure");


	Map<String,String> diagrams = new TreeMap<String,String>();
	
	if (source != null && target != null) {
	    for (StructureMapGroupRuleComponent rule : group.getRule()) {
		log("looking for arrows under top level rule: \"" + rule.getName() + "\"");
		calculateArrows(rule,svars,tvars);
	    }
	    try {
		diagrams.put("overview",renderOverviewStructureMapUML(map,source,target) );
		diagrams.put("full",renderFullStructureMapUML(map,source,target) );
	    } catch (Exception e) {
		log("Unable to create uml for "  + map.getName() + ":" + e.toString());
	    }
	}
	return diagrams;
    }



    protected String renderFullStructureMapUML(StructureMap map,StructureDefinition source,StructureDefinition target) throws FHIRException {

	// String dependentRules = "";
	// for (String r: getDependentRules()) {
	//     dependentRules 
	// 	+= "    node" + dotSanatize(r) + " " + " [\n"
	// 	+  "     shape=\"folder\"\n"
	// 	+  "     label=\"" + r + "\"\n"
	// 	+  "    ]\n";		
	//     for (String p: getDependents(r)) {
        //         log("adding rule " + r + " with dependent " + p);
	// 	if (svars.containsKey(p)) {
	// 	    arrowList
	// 		+= "   node" + dotSanatize(source.getName()) + ":" + getSPort(svars.get(p)) + " -> node" + dotSanatize(r) + "\n";
	// 	} else if (tvars.containsKey(p)) {
	// 	    arrowList
	// 		+= "   node" + dotSanatize(r)  + " -> node" + dotSanatize(target.getName()) + ":" + getTPort(tvars.get(p)) + " \n";
	// 	} else {
	// 	    log("Dependent rule " + r + " could not find variable " + p);
	// 	}
	//     }
	// }


	// String digraph  
	//     =  " digraph " + dotSanatize(map.getName()) + " {\n"
	//     + "  subgraph cluster" + dotSanatize(map.getName()) + "{\n"
	//     + "   label=<<font color=\"blue\">" + dotSanatize(map.getName()) + "</font>>\n"
	//     + "   href=\"" + map.getUrl() + "\"\n"
	//     + "   tooltip=\"" + map.getUrl() + "\"\n"
	//     + "   labeloc=\"top\"\n"
	//     + "   {rank = same; node" + dotSanatize(source.getName()) + ";" + dependentNodes + " node" + dotSanatize(target.getName()) + "; }\n"
	//     +     sourceNode
	//     +     targetNode
        //     +     dependentRules
	//     +     arrowList
	//     + "  }\n"
	//     + " }\n";
	// log(digraph);

	// return
	//     "@startuml" + "\n" 
	//     + " skinparam groupInheritance 2" + "\n"
	//     +   digraph
	//     + "@enduml" + "\n";
	
	return "";
    }

    protected String renderOverviewStructureMapUML(StructureMap map, StructureDefinition source,StructureDefinition target) throws FHIRException {
	//maps are variable name to element path
	
	String sout = "";
	for (String s: svars.keySet()) {
	    sout += "\n     " + s + "->" + svars.get(s) ;
	}
	log("  Source Vars=" + sout);
	String tout = "";
	for (String t: tvars.keySet()) {
	    tout += "\n     " + t + "->" + tvars.get(t) ;
	}
	log("  Target Vars=" + tout);

	
	String svarRows = "      <tr><td align=\"left\" bgcolor=\"lightgray\" border=\"0\">" + source.getName() + "</td></tr>\n";
	for (String k: svars.keySet()) {
	    log("adding source row " + k + " pointing at " + svars.get(k));
	    String format = "align=\"left\"";
	    if (k.equals(".")) {
		format += " bgcolor=\"lightgray\" border=\"0\"";
	    }
	    String svar = svars.get(k);
	    if (svar.startsWith(".")) {
		svarRows += "      <tr><td align=\"left\" port=\"" + getSPort(svar) + "\">" + svars.get(k) + " as " + k + "</td></tr>\n";
	    } else {
		svarRows += "      <tr><td align=\"left\" port=\"" + getSPort(svar) + "\">" + svars.get(k)  + "</td></tr>\n";
	    }
	}
	String tvarRows = "      <tr><td align=\"left\" bgcolor=\"lightgray\" border=\"0\">" + target.getName() + "</td></tr>\n";
	for (String k: tvars.keySet()) {
	    log("adding target row " + k + " pointing at " + tvars.get(k));
	    String tvar = tvars.get(k);
	    tvarRows += "      <tr><td align=\"left\" port=\"" + getTPort(tvar) + "\">" + tvars.get(k) + " as " + k + "</td></tr>\n";
	}      
	
	
	String sourceNode
	    = "   node" + dotSanatize(source.getName()) + " [\n"
	    + "    shape=\"none\"\n"
	    + "    href=\"" + source.getUrl() + "\"\n"
	    + "    tooltip=\"" + source.getUrl() + "\"\n" 
	    + "    label=<\n"
	    + "     <table>\n"
	    + svarRows
	    + "     </table>\n"
	    + "    >\n"
	    + "   ]\n";

	String targetNode
	    = "   node" + dotSanatize(target.getName()) + " [\n"
	    + "    shape=\"none\"\n"
	    + "    href=\"" + target.getUrl() + "\"\n"
	    + "    tooltip=\"" + target.getUrl() + "\"\n" 
	    + "    label=<\n"
	    + "     <table>\n"
	    + tvarRows
	    + "     </table>\n"
	    + "    >\n"
	    + "   ]\n";

	String arrowList = "";
	
	for (String r: getArrowRules()) {
	    for (String s: getArrowSources(r)) {
		for (String t: getArrows(r,s)) {	    
		    log("Adding arrow for " + s + " to " + t);
		    arrowList
			+= "   node" + dotSanatize(source.getName()) + ":" + getSPort(s)
			+  " -> node" + dotSanatize(target.getName()) + ":" + getTPort(t) + "\n";
		}
	    }
	}


	String dependentRules = "";
	String dependentNodes = "";
	for (String r: getDependentRules()) {
	    dependentRules 
		+= "    node" + dotSanatize(r) + " " + " [\n"
		+  "     shape=\"point\"\n"
		+  "    ]\n";
	    dependentNodes += " node" + dotSanatize(r) + ";";
	    for (String p: getDependents(r)) {
                log("adding rule " + r + " with dependent " + p);
		if (svars.containsKey(p)) {
		    arrowList
			+= "   node" + dotSanatize(source.getName()) + ":" + getSPort(svars.get(p)) + " -> node" + dotSanatize(r) + " [arrowhead=\"none\"]\n";
		} else if (tvars.containsKey(p)) {
		    arrowList
			+= "   node" + dotSanatize(r)  + " -> node" + dotSanatize(target.getName()) + ":" + getTPort(tvars.get(p)) + " \n";
		} else {
		    log("Dependent rule " + r + " could not find variable " + p);
		}
	    }
	}

	String digraph  
	    =  " digraph " + dotSanatize(map.getName()) + " {\n"
	    + "  subgraph cluster" + dotSanatize(map.getName()) + "{\n"
	    + "   label=<<font color=\"blue\">" + dotSanatize(map.getName()) + "</font>>\n"
	    + "   href=\"" + map.getUrl() + "\"\n"
	    + "   tooltip=\"" + map.getUrl() + "\"\n"
	    + "   labeloc=\"top\"\n"
	    + "   {rank = same; node" + dotSanatize(source.getName()) + ";" + dependentNodes + " node" + dotSanatize(target.getName()) + "; }\n"
	    +     sourceNode
	    +     targetNode
            +     dependentRules
	    +     arrowList
	    + "  }\n"
	    + " }\n";
	log(digraph);

	return
	    "@startuml" + "\n" 
	    + " skinparam groupInheritance 2" + "\n"
	    +   digraph
	    + "@enduml" + "\n";
    }

  


    String dotSanatize(String s) {
	return s.replaceAll("[\\W]|_", "_");
    }


    protected Map<String,String> sports = new HashMap<String,String>();

    protected String getSPort(String s) {
	if (!sports.containsKey(s)) {
	    log("Cannot find source port for " + s);
	}
	return sports.get(s);
    }
    protected void addSPort(String s) {
	if (sports.containsKey(s)) {
	    return;
	}
	String sport;
	if (s.startsWith("ValueString(")) {
	    sport = "C" + sports.size();
	} else {
	    sport = dotSanatize(s);
	}
	log("Adding source port " + s + " as " + sport);       	
	sports.put(s,sport);
    }


    protected Map<String,String> tports = new HashMap<String,String>();

    protected String getTPort(String t) {
	if (!tports.containsKey(t)) {
	    log("Cannot find target port for " + t);
	}
	return tports.get(t);
    }
    protected void addTPort(String t) {
	if (tports.containsKey(t)) {
	    return;
	}
	String tport = dotSanatize(t);
	log("Adding target port " + t + " as " + tport);
	tports.put(t,tport);
    }

    
    protected void calculateArrows(StructureMapGroupRuleComponent rule, Map<String,String> svars, Map<String,String> tvars) {
	log("calculating arrows on rule \"" + rule.getName() + "\"");
	String sout = "";
	for (String s: svars.keySet()) {
	    sout += "\n     " + s + "->" + svars.get(s) ;
	}
	log("  Source Vars=" + sout);
	String tout = "";
	for (String t: tvars.keySet()) {
	    tout += "\n     " + t + "->" + tvars.get(t) ;
	}
	log("  Target Vars=" + tout);

	for (StructureMapGroupRuleSourceComponent source : rule.getSource()) {
	    if  ( ! source.hasContext()
		  || ! source.hasVariable()
		  || ! source.hasElement()		  
		  || ! tvars.containsKey(source.getContext())
		) {
		continue;
	    }
	    log("    found context (" + source.getContext() + ") in rule for " + svars.get(source.getContext()) + " on source element " + source.getElement() + " to be called as " + source.getVariable());
	    log("    Adding source var " + source.getVariable() + "-> " + svars.get(source.getContext()) + "." + source.getElement());
	    String sPath = svars.get(source.getContext()).replaceAll("\\.*$","");
	    svars.put(source.getVariable(),sPath + "." + source.getElement());
	}
	
	for (StructureMapGroupRuleTargetComponent target : rule.getTarget()) {
	    if  ( ! target.hasContext()
		  || ! target.hasElement()		  
		  || ! tvars.containsKey(target.getContext())
		) {
		continue;
	    }
	    log("    found context (" + target.getContext() + ") in rule for " + tvars.get(target.getContext()) + " on target element " + target.getElement() );
	    String tPath = tvars.get(target.getContext()).replaceAll("\\.$","");
	    if (target.hasVariable()) {
		log("    Adding target var " + target.getVariable() + "-> " + tPath + "." + target.getElement());
		tvars.put(target.getVariable(),tPath  + "." + target.getElement());
	    }
	    if (!target.hasTransform() ) {
		continue;
	    }
	    log("    Found target var " + target.getContext() + "." + target.getElement() + " with tranform " + target.getTransform());
	    log("    Found target var from context " + tvars.get(target.getContext()) );
	    String targetVar = tPath + "." + target.getElement();
	    log("tPath = " + tPath + " tVar = " + targetVar);
	    tvars.put(target.getContext() + "." +  target.getElement(),targetVar );
	    addTPort(targetVar);
	    switch (target.getTransform()) {
	    case TRANSLATE:		    
	    case APPEND:
	    case POINTER:
	    case COPY:
	    case CC:
	    case C:
	    case REFERENCE:
	    case CAST:
	    case TRUNCATE:
		StructureMapGroupRuleTargetParameterComponent sourceParam = target.getParameter().get(0);
		if ( sourceParam == null) {
		    log("  no parameter set");
		    break;
		}
		if ( sourceParam.hasValueIdType()) {
		    IdType idType =  sourceParam.getValueIdType();
		    if ( !svars.containsKey(idType.asStringValue())) {
			log("   source variable not found " + idType.asStringValue());
			break;
		    }
		    log("      found source parameter for transform =" + idType.asStringValue());
		    addArrow(rule.getName(), svars.get(idType.asStringValue()) , targetVar);
		    addSPort(svars.get(idType.asStringValue()));
		} else {
		    String constantVal = retrieveConstant(sourceParam);
		    if (constantVal != null) {
			addArrow(rule.getName(),constantVal,targetVar);
		    }
		}
		break;
	    case CREATE:
	    case EVALUATE:				
	    case ESCAPE:
	    case DATEOP:
		//should we do something with these 
		break;
	    }
	}


	for (StructureMapGroupRuleDependentComponent dep: rule.getDependent()) {
	    log("    Looking  dependent rule " + rule.getName());
	    for (StructureMapGroupRuleTargetParameterComponent param: dep.getParameter()) {
		if ( param.hasValueIdType()) {		    
		    IdType idType =  param.getValueIdType();
		    String id = idType.asStringValue();
		    String paramName = svars.get(id);
		    log("      Examining depdendent variable: " + id + " via " + paramName);
		    if (svars.containsKey(id)) {
			//addDependent(rule.getName(),svars.get(paramName));
			addDependent(rule.getName(),id);
		    } else if  (tvars.containsKey(id)) {
			addDependent(rule.getName(),id);
			//addDependent(rule.getName(),tvars.get(paramName));
		    } else {
			log("Could not find depdendent variable: " + id + " via " + paramName);
		    }
		} else {
		    String constantVal = retrieveConstant(param);
		    if (constantVal != null ) {
			addDependent(rule.getName(),constantVal);
		    }
		}
	    }
	}
	
	for (StructureMapGroupRuleComponent r: rule.getRule()) {
	    log("Walking down rule \"" + r.getName() + "\"");
	    calculateArrows(r,svars,tvars);
	}
    }



    protected List<String> getDependents(String dependent) {
	if (!dependents.containsKey(dependent)) {
	    dependents.put(dependent,new ArrayList<String>());
	}
	return dependents.get(dependent);
    }
    protected void addDependent(String dependent, String param) {
	List<String> params = getDependents(dependent);
	params.add(param);
    }
    protected Set<String> getDependentRules() {
	return dependents.keySet();
    }



    protected Map<String,List<String>> getArrows(String rule) {
	if (!arrows.containsKey(rule)) {
	    Map<String,List<String>> arrowList = new HashMap<String,List<String>>();
	    arrows.put(rule,arrowList);
	}
	return arrows.get(rule);
    }
    
    protected List<String> getArrows(String rule, String source) {
	Map<String,List<String>> arrowList = getArrows(rule);
	if (!arrowList.containsKey(source)) {
	    arrowList.put(source, new ArrayList<String>());
	}
	return arrowList.get(source);
    }
    protected void addArrow(String rule, String source,String target) {
	List<String> targets = getArrows(rule,source);
	targets.add(target);
    }


    protected Set<String> getArrowSources(String rule) {
	return getArrows(rule).keySet();
    }
    protected Set<String> getArrowRules() {
	return arrows.keySet();
    }       



    protected String retrieveConstant(StructureMapGroupRuleTargetParameterComponent param) {
	String val = null;
	if (param.hasValueStringType()) {
	    log("       found string value type");
	    StringType stringType =  param.getValueStringType();	    
	    String stringVal = stringType.asStringValue();
	    val = "ValueString(\"" + stringVal + "\")";
	    log("      found source string for transform =" + stringVal);
	    // we want to indicate a constant value is mapped to the target
	}
	if (val != null) {
	    svars.put(val,val);
	    addSPort(val);
	}
	return val;
    }
    
}
