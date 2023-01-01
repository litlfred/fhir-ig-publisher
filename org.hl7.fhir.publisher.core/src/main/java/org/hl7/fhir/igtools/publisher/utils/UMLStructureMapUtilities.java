package org.hl7.fhir.igtools.publisher;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r5.conformance.ProfileUtilities.ProfileKnowledgeProvider;
import org.hl7.fhir.igtools.publisher.IGKnowledgeProvider;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.context.IWorkerContext.ILoggingService.LogCategory;
import org.hl7.fhir.r5.model.ElementDefinition;
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
    public Map<String,String> renderUML(StructureMap map)   {
	Map<String,String> diagrams = new HashMap<String,String>();
	try {
	    String umlSource = "@startuml" + "\n" 
		+ "skinparam groupInheritance 2" + "\n"
		+ renderStructureMapUML(map) + "\n"
		+ "@enduml" + "\n";
	    diagrams.put("overview",umlSource);
	    //  renderStructureMapGroupDetails(map.getGroup(),diagrams);
	} catch (Exception e) {
	    log("Unable to create uml for "  + map.getName() + ":" + e.toString());
	}
	return diagrams;
    }


    private List<String> getReferencedElementsBySource(StructureMap map) {
	List<String> sources = new ArrayList<String>();
	for (StructureMapGroupComponent group: map.getGroup()) {
	    for (StructureMapGroupInputComponent input : group.getInput() ) {
		if (input.getMode() != StructureMapInputMode.SOURCE) {
		    continue;
		}
		for (StructureMapGroupRuleComponent rule : group.getRule()) {
		    getReferencedElementsBySource(rule,input.getName(),"",sources);
		}
	    }	    
	}
	return sources;
	
    }
    
    
    private List<String> getReferencedElementsByTarget(StructureMap map) {
	List<String> targets = new ArrayList<String>();
	for (StructureMapGroupComponent group: map.getGroup()) {
	    for (StructureMapGroupInputComponent input : group.getInput() ) {
		if (input.getMode() != StructureMapInputMode.TARGET) {
		    continue;
		}
		for (StructureMapGroupRuleComponent rule : group.getRule()) {
		    getReferencedElementsByTarget(rule,input.getName(),"",targets);
		}
	    }
	}
	return targets;
    }
    
    private String  renderSourceStructureDefinition(StructureDefinition sd,  StructureMap map) {
	List<String> sources = new ArrayList<String>();
	StructureMapGroupComponent group = map.getGroupFirstRep();
	log("Rendering Source SD - Found group " + group.getName() + " while looking for " + sd.getId());
	log("SD ftype " + sd.fhirType());
	log("SD gettype " + sd.getType());
	log("SD gettypename " + sd.getTypeName());
	log("SD getname " + sd.getName());
	String sourceAlias  = null;
	String targetAlias = null;

	for (StructureMapStructureComponent structure : map.getStructure()) {
	    log("Got structure: " + structure.getAlias());
	    if (structure.getMode() == StructureMapModelMode.SOURCE) {
		sourceAlias = structure.getAlias();
	     } else if (structure.getMode() == StructureMapModelMode.TARGET) {
		targetAlias = structure.getAlias();
	    }	    
	}

	log("render source - SA=" + sourceAlias + "TA=" + targetAlias);
	
	for ( StructureMapGroupInputComponent input : group.getInput()) {
	    log("scanning source for input:" + input.getName());
	    if (sourceAlias.equals(input.getType())) {
		log("Looking at group input source Name(Type): " + input.getName() + "(" + input.getType() + ")");
		for (StructureMapGroupRuleComponent rule : group.getRule()) {
		    for (StructureMapGroupRuleSourceComponent s : rule.getSource()) {
			log("scanning rule \"" + rule.getName() + "\" for source " + input.getName() + "(" + input.getType() + ") against " + s.getContext());
			getReferencedElementsBySource(rule,s.getVariable(), sd.getName() + "." ,sources);
			//getReferencedElementsBySource(rule,rule.getVariable(), sd.getName() + "." ,sources);
		    }
		}
		
	    } else if (targetAlias.equals(input.getType())) {
		log("Looking at group input source Name(Type): " + input.getName() + "(" + input.getType() + ")");
		for (StructureMapGroupRuleComponent rule : group.getRule()) {
		    for (StructureMapGroupRuleTargetComponent t : rule.getTarget()) {
			log("scanning rule " + rule.getName() + " for source " + input.getName() );
			//getReferencedElementsByTarget(rule,t.getVariable(), sd.getName() + "." ,sources);
		    }
		}		    
	    }
	    // 	log("skipping: Not source");
	    // 	continue;
	    // }
	    //group.
            //	    input.getType().equals(

	}

        log("Got sources: " + String.join(",",sources));
	String out = "class " + sd.getName() + " { " + "\n";    
	StructureDefinitionSnapshotComponent snapshot = sd.getSnapshot();	
	for (ElementDefinition elem : snapshot.getElement()) {
	    ArrayList<String> types = new ArrayList<String>();
	    if (!sources.contains(elem.getPath())) {
		log("Skipping :" + elem.getPath() + " as not in " + String.join(",",sources));
		continue;
	    }

	    for (ElementDefinition.TypeRefComponent component : elem.getType()) {
		types.add(component.getName());
		//maybe types.push(component.toString());
	    }
	    //maybe use elem.getName()
	    out += "  " + elem.getPath() + " " + String.join(",",types) +  "\n";
	}
	out += "}" + "\n";
	return out;	
    }


    private String  renderTargetStructureDefinition(StructureDefinition sd, StructureMap map) {
	List<String> targets = new ArrayList<String>();
	StructureMapGroupComponent group = map.getGroupFirstRep();
	log("rendering target SD - Found group " + group.getName() + " while looking for " + sd.getId());
	log("SD ftype " + sd.fhirType());
	log("SD gettype " + sd.getType());
	log("SD gettypename " + sd.getTypeName());
	log("SD getname " + sd.getName());
	String sourceAlias = null;
	String targetAlias = null;
	for (StructureMapStructureComponent structure : map.getStructure()) {
	    log("Got structure: " + structure.getAlias());
	    if (structure.getMode() == StructureMapModelMode.SOURCE) {
		sourceAlias = structure.getAlias();
	     } else if (structure.getMode() == StructureMapModelMode.TARGET) {
		targetAlias = structure.getAlias();
	    }
	    
	}
	log("render Target - SA=" + sourceAlias + "TA=" + targetAlias);

	for ( StructureMapGroupInputComponent input : group.getInput())  {
	    log("scanning target for input:" + input.getName());
	    if (input.getMode() != StructureMapInputMode.TARGET) {
		log("skipping: Not target");
		continue;
	    }
	    log("Looking at structure input target: " + input.getName() + " (" + input.getType() +"->" + sd.getName() + ")");
	    // for (StructureMapGroupRuleComponent rule : group.getRulbe()) {
	    // 	getReferencedElementsByTarget(rule,input.getName(),sd.getName() . "." ,targets);
	    // }
	    log("Checking " + targetAlias + " or " + sourceAlias + "=" + input.getType());
	    if (sourceAlias.equals(input.getType())) {
		log("Looking at sourcealias match for group input source Name(Type): " + input.getName() + "(" + input.getType() + ")");
		for (StructureMapGroupRuleComponent rule : group.getRule()) {
		    log(" ------ at sourcealias rule \"" + rule.getName() + "\"");
		    for (StructureMapGroupRuleSourceComponent source : rule.getSource()) {
			log(" ------ ------------ rule source " + source.getContext() + "." + source.getElement() + " as " + source.getVariable());
			if ( input.getName().equals(source.getContext() )) {
			    log("SmurfA:"  + source.getContext());
			    log("scanning rule \"" + rule.getName() + "\" for source " + input.getName() + "(" + input.getType() + ")");
			    getReferencedElementsBySource(rule,source.getVariable(), sd.getName() + "." ,targets);
			}
		    }
		    
		}		    
		
	    } else if (targetAlias.equals(input.getType())) {
		log("Looking at targetalias match for group input source Name(Type): " + input.getName() + "(" + input.getType() + ")");
		for (StructureMapGroupRuleComponent rule : group.getRule()) {
		    log(" ------ at targetalias rule \"" + rule.getName() + "\"");
		    for (StructureMapGroupRuleSourceComponent source : rule.getSource()) {
			log(" ------ ------------ rule source " + source.getContext() + "." + source.getElement() + " as " + source.getVariable());
			if ( input.getName().equals(source.getContext() )) {
			    log("SmurfBS:"  + source.getContext());  //We have a match
			    String arrowSource = sd.getName() + "." + source.getElement();
			    log("Adding arrow source " + arrowSource);
			    targets.add(arrowSource);
			    log("scanning rule \"" + rule.getName() + "\" for source " + arrowSource + " on source variable " + source.getVariable());
			    getReferencedElementsBySource(rule,source.getVariable(), arrowSource + "." ,targets);
			}
		    }

		    for (StructureMapGroupRuleTargetComponent target : rule.getTarget()) {
			log(" ------ ------------ rule target " + target.getContext() + "." + target.getElement());
			if ( input.getName().equals(target.getContext() )) {
			    log("SmurfBT:"  + target.getContext());
			}

			log("scanning rule \"" + rule.getName() + "\" for source " + input.getName() );
			//getReferencedElementsByTarget(rule,t.getVariable(), sd.getName() + "." ,targets);
		    }
		}		    
	    }

	    
	}
	
        log("Got targets: " + String.join(",",targets));
	String out = "class " + sd.getName() + " { " + "\n";	    
	StructureDefinitionSnapshotComponent snapshot = sd.getSnapshot();	
	for (ElementDefinition elem : snapshot.getElement()) {
	    ArrayList<String> types = new ArrayList<String>();
	    if (!targets.contains(elem.getPath())) {
		continue;
	    }
	    log("Matched element:" + elem.getPath() + " in targets " + String.join(",",targets));
	    for (ElementDefinition.TypeRefComponent component : elem.getType()) {
		types.add(component.getName());
		//maybe types.push(component.toString());
	    }
	    //maybe use elem.getName()
	    out += "  " + elem.getPath() + " " + String.join(",",types) +  "\n";
	}
	out += "}" + "\n";
	return out;	
    }



    


    private String renderStructureMapGroups(List<StructureMapGroupComponent> sgs) {
	String out = "";
	for (StructureMapGroupComponent sg : sgs) {
	    out += renderGroupArrows(sg);
	    out += renderStructureMapGroup(sg);
	}
	return out;
	
    }


    private String renderGroupArrows(StructureMapGroupComponent sg) {
	String out = "";
	for (StructureMapGroupInputComponent input : sg.getInput() ) {
	    if (input.getMode() == StructureMapInputMode.SOURCE) {
		for (StructureMapGroupInputComponent target : sg.getInput() ) {
		    if (target.getMode() != StructureMapInputMode.TARGET) {
			continue;
		    }
		    out += input.getName() + " --> " + target.getName() + "\n";
		}
		
	    }
	}
	return out;
    }

    private String  renderStructureMapGroup(StructureMapGroupComponent group) {
	String out = "class " + group.getName() + " { \n";
	boolean found = false;
	for (StructureMapGroupInputComponent input : group.getInput() ) {
	    if (input.getMode() != StructureMapInputMode.SOURCE) {
		continue;
	    }
	    if (!found) {
	    	out += "..Sources..\n";
	    }
	    found = true;
	    out += "  " + input.getType() + " " + input.getName() + "\n";
	    List<String> elements = new ArrayList<String>();
	    for (StructureMapGroupRuleComponent rule : group.getRule()) {
		List<String> sourceRules = new ArrayList<String>();
		getReferencedElementsBySource(rule,input.getName(),input.getName() + "." ,sourceRules);
		Collections.sort(sourceRules);
		for (String sourceRule : sourceRules) {
		    out += sourceRule + "\n";
		}
	    }
	}
	found = false;
	for (StructureMapGroupInputComponent input : group.getInput() ) {
	    if (input.getMode() != StructureMapInputMode.TARGET) {
		continue;
	    }
	    if (!found) {
	    	out += "..Targets..\n";
	    }	    
	    found = true;
	    out += "  " + input.getType() + " " + input.getName() + "\n";
	    List<String> elements = new ArrayList<String>();
	    for (StructureMapGroupRuleComponent rule : group.getRule()) {
		List<String> targetRules = new ArrayList<String>();
		getReferencedElementsByTarget(rule,input.getName(),input.getName() + "." ,targetRules);
		Collections.sort(targetRules);
		for (String targetRule : targetRules) {
		    out += targetRule + "\n";
		}
	    }
	}
	found = false;
	for (StructureMapGroupInputComponent input : group.getInput() ) {
	    if (input.getMode() != StructureMapInputMode.NULL) {
		continue;
	    }
	    if (!found) {
	    	out += "..Null..\n";
	    }
	    out += "  " + input.getType() + " " + input.getName() + "\n";
	}
	out += "}\n";
	
	return out;	
    }

    private List<String> getReferencedElementsByTarget(StructureMapGroupRuleComponent rule, String name) {
	List<String> rules = new ArrayList<String>();
	getReferencedElementsByTarget(rule,name,"",rules);
	return rules;
    }
    
    private List<String> getReferencedElementsByTarget(StructureMapGroupRuleComponent rule, String name, String prefix, List<String> rules) {
	log("Scanning for targets [" + prefix + "]" + name + " in rule \"" + rule.getName()  + "\"");
	//look for the incoming target as we we walk down the tree of rules
	for (StructureMapGroupRuleComponent r : rule.getRule()) {
	    log("Walking down rule, looking for targets: \n" + r.getName() + "\" on element path " + prefix + name );
	    getReferencedElementsByTarget(r,name, prefix, rules );
	}
	for (StructureMapGroupRuleTargetComponent t : rule.getTarget()) {
	    log("Looking at target context " + t.getContext() + " with [" + prefix + "]" + name + " on target elem " +  t.getElement() );
	    if (!t.hasElement() || !name.equals(t.getContext() )
		) {
		log("no match for " + name + "=" + t.getContext());
		continue;
	    }
	    log("match for " + name + "=" + t.getContext());
	    String element = t.getElement();
	    String variable = t.getVariable();
	    if (variable == null || variable.isBlank()) {
		variable = name;
	    }
	    //String type = t.getType();
	    for (StructureMapGroupRuleComponent r : rule.getRule()) {
		getReferencedElementsByTarget(r, variable, prefix + element  + ".",rules);
	    }
	    if ( !name.equals(t.getContext() ) ){
		continue;
	    }
	    String out = prefix + name;
	    // if (t.hasType() ) {
	    // 	out += " : " + t.getType();
	    // }
	    if (!rules.contains (out)) {
		rules.add( out);
	    }

	}
	return rules;
    }


    private List<String> getReferencedElementsBySource(StructureMapGroupRuleComponent rule, String name) {
	List<String> rules = new ArrayList<String>();
	return getReferencedElementsBySource(rule,name,"",rules);
    }
    
    private List<String> getReferencedElementsBySource(StructureMapGroupRuleComponent rule, String name, String prefix, List<String> rules) {

	for (StructureMapGroupRuleComponent r : rule.getRule()) {
	    getReferencedElementsBySource(r,name, prefix, rules );
	}

	for (StructureMapGroupRuleSourceComponent s : rule.getSource()) {
	    if (! s.hasElement() || !name.equals(s.getContext())) {
		continue;
	    }
	    String element = s.getElement();
	    String variable = s.getVariable();
	    if (variable == null || variable.isBlank()) {
		variable = name;
	    }
	    //String type = s.getType();
	    for (StructureMapGroupRuleComponent r : rule.getRule()) {
		getReferencedElementsBySource(r, variable, prefix + element  + ".",rules);
	    }
	    
	    if ( !name.equals(s.getContext() ) ){
		continue;
	    }
	    String out = prefix + name;
	    if (s.hasType()) {		
		out += " : " + s.getType();
	    }
	    if (!rules.contains (out)) {
		rules.add( out);
	    }
	}
	return rules;
    }
	

    private String renderStructureMapUML(StructureMap map) {

	String sourceSD = null;
	String targetSD = null;
	String sourceAlias = null;
	String targetAlias = null;
	//map is variable name to element path
	Map<String,String> vars = new HashMap<String,String>();
	StructureDefinition source = null;
	StructureDefinition target = null;
	StructureMapGroupComponent group = map.getGroupFirstRep();
	log("looking for arrow components in group " + group.getName());

	for (StructureMapStructureComponent structure : map.getStructure()){
	    log("Checking structure struccture of map:" + structure.getAlias());
	    if (structure.getMode() == StructureMapModelMode.TARGET) {
		log("found target:" + structure.getUrl() + " as " + structure.getAlias() );
		targetSD = structure.getUrl();
		targetAlias = structure.getAlias();
		target = (StructureDefinition) worker.fetchResource(StructureDefinition.class, targetSD);
		for ( StructureMapGroupInputComponent input : group.getInput())  {
		    log("Scanninng input (" + input.getName() + ") with type <" + input.getType() + ">") ;
		    if (! targetAlias.equals(input.getType())) {
			continue;
		    }
		    log("  Adding to vars:" + target.getName() + " as " + targetAlias );
		    vars.put(input.getName(),target.getName()); //or .getType() or getTypeName()?
		}
		
	    } else if (structure.getMode() == StructureMapModelMode.SOURCE) {
		log("found source: " + structure.getUrl() + " as " + structure.getAlias() );
		sourceSD = structure.getUrl();
		sourceAlias = structure.getAlias();
		source = (StructureDefinition) worker.fetchResource(StructureDefinition.class, sourceSD);
		for ( StructureMapGroupInputComponent input : group.getInput())  {
		    log("Scanninng input (" + input.getName() + ") with type <" + input.getType() + ">") ;
		    if (! sourceAlias.equals(input.getType())) {
			continue;
		    }
		    log("  Adding to vars:" + source.getName() + " as " + sourceAlias );
		    vars.put(input.getName(),source.getName()); //or .getType() or getTypeName()?
		}

	    }      
	}
	log("source SD ftype " + source.fhirType());
	log("source SD gettype " + source.getType());
	log("source SD gettypename " + source.getTypeName());
	log("source SD getname " + source.getName());
	log("target SD ftype " + target.fhirType());
	log("target SD gettype " + target.getType());
	log("target SD gettypename " + target.getTypeName());
	log("target SD getname " + target.getName());

	List<String> arrows = new ArrayList<String>();	
	for (StructureMapGroupRuleComponent rule : group.getRule()) {
	    log("looking for arrows under top level rule: \"" + rule.getName() + "\"");
	    calculateArrows(rule,vars,arrows);
	}


	String[] sources = vars.values().toArray(new String[vars.values().size()]);
	log("Got variables: " + String.join(",",sources));

	String sourceClassUML = "class " + source.getName() + " {\n " + String.join("\n",sources) + "\n}\n";
//	String targetClassUML = "class " + target.getName() + " {\n " + String.join("\n",targets) + "\n}\n";	    

	String out = "package \"" + map.getName() + "\" {" + "\n"
	    + "  namespace \"" + sourceSD + "\"\n{\n" + sourceClassUML + "} " +  "\n"
//	    + "  namespace \"" + map.getName() + "\" {\n" +  renderStructureMapGroups(map.getGroup()) + "\n} " + "\n"
//	    + "  namespace \"" + targetSD + "\"\n{" + targetClassUML + "\n} " + "\n"
	    + "} " + "\n";
	return out;    
    }

    private void calculateArrows(StructureMapGroupRuleComponent rule, Map<String,String> vars, List<String> arrows) {
	log("calculating arrows on rule \"" + rule.getName() + "\"");
	log("Source Keys=" + String.join(",",vars.keySet().toArray(new String[vars.keySet().size()])));
	log("Sources=" + String.join(",",vars.values().toArray(new String[vars.values().size()])));
	for (StructureMapGroupRuleSourceComponent src : rule.getSource()) {
	    log("scanning src with context " + src.getContext());
	    String context = src.getContext();
	    if  (vars.containsKey(context)) {
		log("found context (" + context + ") in rule for " + vars.get(context) + " on src element " + src.getElement() + " to be called as " + src.getVariable());
		if (src.hasVariable() && src.hasElement()) {
		    log("Adding source var " + src.getVariable() + "-> " + vars.get(context) + "." + src.getElement());
		    vars.put(src.getVariable(),vars.get(context) + "." + src.getElement());
		} else {
		    vars.put(context+ "." + src.getElement() , vars.get(context) + "." + src.getElement());
		}
	    } else {
		log("No match for source context=" + src.getContext());
	    }
	}
	for (StructureMapGroupRuleTargetComponent trgt : rule.getTarget()) {
	    log("scanning trgt with context " + trgt.getContext());
	    String context = trgt.getContext();
	    if  (vars.containsKey(context)) {
		log("found context (" + context + ") in rule for " + vars.get(context) + " on trgt element " + trgt.getElement() );
		if (trgt.hasVariable() && trgt.hasElement()) {
		    log("Adding source var " + trgt.getVariable() + "-> " + vars.get(context) + "." + trgt.getElement());
		    vars.put(trgt.getVariable(),vars.get(context) + "." + trgt.getElement());
		} else {
		    vars.put(context + "." + trgt.getElement() , vars.get(context) + "." + trgt.getElement());
		}
	    }  else {
		log("No match for target context=" + trgt.getContext());
	    }
	    
	    if (!trgt.hasTransform() ) {
		continue;
	    }
	    log("transforms -  need to add arrows");
	    //this is the end of the road
	    //should create arrows here
	    //arrows.add(arrow)
	    StructureMapTransform transform = trgt.getTransform();
	    String targetPath = "";
	    String targetVar  = trgt.getContext();
	    if (vars.containsKey(targetVar)) {
		targetPath = vars.get(targetVar);
	    } else {
		log("Cannot find target var " + targetVar);
		continue;
	    }

	    switch (transform) {
	    case TRANSLATE:		    
	    case APPEND:
	    case POINTER:
	    case COPY:
	    case CC:
	    case C:
	    case REFERENCE:
	    case CAST:
	    case TRUNCATE:
		StructureMapGroupRuleTargetParameterComponent param = trgt.getParameter().get(0);
		if (param != null ) {
		    String sourceVar = param.getValueIdType().asStringValue();
		    log("sourceVar= " + sourceVar);
		    if (sourceVar != null && !sourceVar.isBlank() && !vars.containsKey(sourceVar)) {
			String arrow =  vars.get(sourceVar) +  " -->" + targetPath + " : " + rule.getName() + "\n";
			log("Making arrow=" + arrow);
			arrows.add(arrow);
    		    }
		}
		break;
	    case CREATE:
	    case EVALUATE:				
	    case ESCAPE:
	    case DATEOP:
		break;
	    }
	}
	if (rule.hasDependent()) {
	    log("found dependants - need to add arrows");
							     
	}
	for (StructureMapGroupRuleComponent r: rule.getRule()) {
	    log("Walking down rule \"" + r.getName() + "\"");
	    calculateArrows(r,vars,arrows);
	}
    }
    
	


    
	// 	    //we have a match against a source of the mapand the rules in group:
	// 	    // private Array<String> buildArrows(StructureMapGroupRuleComponent rule, String prefix, Array<String> sources, Array<String> targets)
	// 	    Array<String> arrows = retrieveArrows(rule,sources,targets)
		    
	// 	    log(" ------ at sourcealias rule \"" + rule.getName() + "\"");
	// 		log(" ------ ------------ rule source " + src.getContext() + "." + src.getElement() + " as " + src.getVariable());
	// 		if ( ! input.getName().equals(src.getContext() )) {
	// 		    continue;
	// 		}
			
	// 		//String element = s.getElement();
	// 		//String variable = s.getVariable();
	// 		//if (variable == null || variable.isBlank()) {
	// 		//    variable = name;
	// 		//}

	// 		log("SmurfA:"  + src.getContext());
	// 		log("scanning rule \"" + rule.getName() + "\" for source " + input.getName() + "(" + input.getType() + ")");
	// 		if (input.getMode() == StructureMapInputMode.SOURCE) {
	// 		    String arrowSource = source.getName() + "." + src.getElement();
	// 		    log("Adding arrow source to source: " + arrowSource);
	// 		    sources.add(arrowSource);
	// 		    getReferencedElementsBySource(rule,src.getVariable(), source.getName() + "." ,targets);
	// 		} else if (input.getMode() == StructureMapInputMode.TARGET) {
	// 		    String arrowTarget = target.getName() + "." + src.getElement();
	// 		    log("Adding arrow target to targets: " + arrowTarget);
	// 		    targets.add(arrowTarget);
	// 		}
	// 	    }
	// 	}
	//     }
	// }

	// StructureDefinitionSnapshotComponent snapshot = sd.getSnapshot();	
	// for (ElementDefinition elem : snapshot.getElement()) {
	//     ArrayList<String> types = new ArrayList<String>();
	//     if (!targets.contains(elem.getPath())) {
	// 	continue;
	//     }
	//     log("Matched element:" + elem.getPath() + " in targets " + String.join(",",targets));
	//     for (ElementDefinition.TypeRefComponent component : elem.getType()) {
	// 	types.add(component.getName());
	// 	//maybe types.push(component.toString());
	//     }
	//     //maybe use elem.getName()
	//     out += "  " + elem.getPath() + " " + String.join(",",types) +  "\n";
	// }
	// out += "}" + "\n";
	// return out;	
    

    // // private String renderStuctureMapDetails(StructureMap map, Map<String,String> diagrams) {
    // // 	renderStructureMapGroupDetails(map.getGroup(),diagrams);
    // // }

    // // private void  renderStructureMapGroupDetails(List<StructureMapGroupComponent> groups, Map<String,String>diagrams) {	
    // // 	for (StructureMapGroupComponent group : groups) {
    // // 	    renderStructureMapGroupDetails(group, diagrams);
    // // 	}
    // // }

    // // private String renderStructureMapGroupDetails(StructureMapGroupComponent group,Map<String,String> diagrams) {
    // // 	String out = "package \"" + group.getName() + "\n {\n";
    // // 	for (StructureMapGroupInputComponent input: group.getInput()) {
    // // 	    if (input.getMode() != StructureMapInputMode.SOURCE) {
    // // 		continue;
    // // 	    }
    // // 	    out += "  class " + input.getType() + " " + input.getName() + " {\n";
    // // 	    List<String> elements = new ArrayList<String>();
    // // 	    for (StructureMapGroupRuleComponent rule : group.getRule()) {
    // // 		List<String> sourceRules = getReferencedElementsBySource(rule,input.getName(),input.getName() + "." );
    // //               Collections.sort(targetRules);
    // // 		for (String sourceRule : targetRules) {
    // // 		    out += sourceRule + "\n";
    // // 		}
    // // 	    }
    // // 	    out += "\n  }\n";
    // // 	}
    // // 	out += "  together {\n";
    // // 	for (StructureMapGroupRuleComponent rule : group.getRule()) {
    // // 	    out += renderStructureMapGroupRuleDetails(rule,input.getName());
    // // 	}
    // // 	out += "  }\n";
	    
    // // 	for (StructureMapGroupInputComponent input: group.getInput()) {
    // // 	    if (input.getMode() != StructureMapInputMode.TARGET) {
    // // 		continue;
    // // 	    }
    // // 	    out += "  class " + input.getType() + " " + input.getName() + " {\n";
    // // 	    List<String> elements = new ArrayList<String>();
    // // 	    for (StructureMapGroupRuleComponent rule : group.getRule()) {
    // // 		List<String> targetRules = getReferencedElementsByTarget(rule,input.getName(),input.getName() + "." );
    // //               Collections.sort(targetRules);
    // // 		for (String targetRule : targetRules) {
    // // 		    out += targetRule + "\n";
    // // 		}
    // // 	    }
    // // 	    out += "\n  }\n";
    // // 	}
    // // 	return out;
    // // }




    // // private String renderStructureMapGroupRuleDetails(StructureMapGroupRuleComponent rule, String context) {
    // // 	return renderStructureMapGroupRuleDetails(rule, context, "");
    // // }

    // // private String renderStructureMapGtoupRuleDetails(StructureMapGroupRuleComponent rule, String context, String prefix) {
    // // 	String out =" class \"" + rule.getName() + "\" { \n";
    // // 	boolean found = false;
    // // 	List<String> subRules = new ArrayList<String>(); 
    // // 	for (StructureMapGroupRuleSourceComponent source : rule.getSource() ) {
    // // 	    String element = source.getElement();
    // // 	    String c = source.getContext();
    // // 	    String variable = source.getVariable();
    // // 	    if (!variable) {
    // // 		variable = c;
    // // 	    }
    // // 	    if (!element || !c ) {
    // // 		continue;
    // // 	    }
    // // 	    out += "  " + source.getType() + " " + prefix + element + "\n";
    // // 	    List<String> sourceRules = getReferencedElementsBySource(rule,variable,prefix + element + "." );
    // // 	    if (sourceRules.count() == 0 ) {
    // // 		continue;
    // // 	    }
    // // 	    if (!found) {
    // // 		out += "..Sources..\n";
    // // 	    }
    // // 	    Collections.sort(sourceRules);
    // // 	    for (String sourceRule : sourceRules) {
    // // 		out += sourceRule + "\n";
    // // 	    }
    // // 	    for (StructureMapGroupRuleComponent r : group.getRule()) {
    // // 		String subRule = renderStructureMapGroupRuleDetails(r, variable ,  prefix + "\"" + r.getName + "\".");
    // // 		if (!subRule || subRules.contains(subRule)) {
    // // 		    continue;
    // // 		}
    // // 		subRules.add(subRule);
    // // 	    }
    // // 	}
    // // 	found = false;
    // // 	for (StructureMapGroupRuleTargetComponent target : rule.getTarget() ) {
    // // 	    String element = target.getElement();
    // // 	    String c = target.getContext();
    // // 	    String variable = target.getVariable();
    // // 	    if (!variable) {
    // // 		variable = c;
    // // 	    }
    // // 	    if (!element || !c ) {
    // // 		continue;
    // // 	    }

    // // 	    out += "  " + target.getType() + " " + target.getName() + "\n";
    // // 	    List<String> targetRules = getReferencedElementsBySource(rule,target.getName(),prefix + target.getName() + "." );
    // // 	    if (targetRules.count() == 0 ) {
    // // 		continue;
    // // 	    }
    // // 	    if (!found) {
    // // 		out += "..Targets..\n";
    // // 	    }
    // // 	    Collections.sort(targetRules);
    // // 	    for (String targetRule : targetRules) {
    // // 		out += targetRule + "\n";
    // // 	    }	    
    // // 	}
    // // 	out += "  }\n";
    // // 	out += renderGroupArrowDetails(rule,context, prefix);
    // // 	for (String subRule : subRules) {
    // // 	    out += subRule;
    // // 	}
    // // 	return out;
    // // }


    // // private String renderGroupArrowDetails(StructureMapGroupRuleComponent rule) {
    // // 	return renderGroupArrowDetails(rule,context,prefix, new HashMap<String,String>());
    // // }


    // // //variables has key the variable name and value the rulename.context.element
    // // private String renderGroupArrowDetails(StructureMapGroupRuleComponent rule, Map<String,String> variables) {
    // // 	String out = "";
    // // 	String context = "\"" + rule.getName() + "\"";
    // // 	for (StructureMapGroupRuleSourceComponent source : rule.getSource() ) {
    // // 	    if (!source.hasElement() || !source.hasContext() ) {
    // // 		continue;
    // // 	    }
    // // 	    if ( source.hasVariable()) {		
    // // 		String var = source.getVariable();
    // // 		if (!variables.containsKey(var)) {
    // // 		    String sourceFieldName = context + "." + source.getContext() + "." + source.getElement();
    // // 		    variables.put(var,sourceFieldName);
    // // 		    variables.put(source.getContext()+ "." + source.getElement(),sourceFieldName);
    // // 		}
    // // 	    }
    // // 	}
    // // 	for (StructureMapGroupRuleTargetComponent target : rule.getTarget() ) {
    // // 	    if (!target.hasElement() || !target.hasContext() || !target.hasVariable()) {
    // // 		continue;
    // // 	    }
    // // 	}

	
    // // 	for (StructureMapGroupRuleTargetComponent target : rule.getTarget() ) {
    // // 	    if (!target.hasElement() || !target.hasContext() ) {
    // // 		continue;
    // // 	    }
    // // 	    String variable = null;
    // // 	    if ( target.hasVariable()) {		
    // // 		variable  = target.getVariable();
    // // 		if (!variables.containsKey(var)) {
    // // 		    String targetFieldName = context + "." + target.getContext() + "." + target.getElement();
    // // 		    variables.put(var,targetFieldName);
    // // 		    variables.put(target.getContext() + "." + target.getElement(),targetFieldName);
    // // 		}
    // // 	    } else {		
    // // 		variable = target.getContext();
    // // 	    }
    // // 	    if (target.hasTransform()) {
    // // 		String transform = target.getTransform();
    // // 		String arrowLabel = rule.getName();;
    // // 		switch (transform) {
    // // 		case StructureMapInputMode.TRANSLATE:		    
    // // 		case StructureMapInputMode.APPEND:
    // // 		case StructureMapInputMode.POINTER:
    // // 		case StructureMapInputMode.COPY:
    // // 		case StructureMapInputMode.CC:
    // // 		case StructureMapInputMode.C:
    // // 		case StructureMapInputMode.REFERNCE:
    // // 		case StructureMapInputMode.CAST:
    // // 		case StructureMapInputMode.TRUNCATE:
    // // 		    StructureMapGroupRuleTargetParameterComponent param = target.getParameter().get(0);
    // // 		    if (param) {
    // // 			String sourceFieldName = param.getValueID(); 
    // // 			if (!sourceFieldName) {
    // // 			    continue;
    // // 			}
    // // 			if (variables.containsKey(sourceFieldName)) { //should be a variable so check
    // // 			    sourceFieldName = variables.get(sourceFieldName);
    // // 			}
    // // 			String targetFieldName = context + "." + element   ;
    // // 			if (variables.containsKey(targetFieldName)) { //dont think it has a variable
    // // 			    targetFieldName = variables.get(targetFieldName);
    // // 			}
    // // 			out += sourceFieldName +  " -->" + targetFieldName + " : " + rule.getName() + "\n";
    // // 		    }
    // // 		    break;
    // // 		case StructureMapInputMode.CREATE:
    // // 		case StructureMapInputMode.EVALUATE:				
    // // 		case StructureMapInputMode.ESCAPE:
    // // 		case StructureMapInputMode.DATEOP:
    // // 		    break;
    // // 		}
    // // 	    }
    // // 	}
	
    // // 	for (StructureMapGroupRuleDependentComponent dependent : rule.getDependent()) {
    // // 	    for (String var : dependent.getVariable()) {

		
    // // 		for (StructureMapGroupRuleTargetParameterComponent param : rule.getParameter()) {
    // // 		    String sourceFieldName = param.getValueID();
    // // 		    if (!sourceFieldName) {
    // // 			continue;
    // // 		    }
    // 		    if (variables.containsKey(sourceFieldName)) { //should be a variable so check
    // 			sourceFieldName = variables.get(sourceFieldName);
    // 		    }


    // 		// String element = t.getElement();
    // 		// String variable = t.getVariable();
    // 		// if (!variable) {
    // 		//     variable = name;
    // 		// }
    // 		// //String type = t.getType();
    // 		// if (!element
    // 		//     || !name.equals(t.getContext() )
    // 		//     ) {
    // 		//     continue;
    // 		// }
    // 		    VariableMode mode = input.getMode() == StructureMapInputMode.SOURCE
    // 		    String targetFieldName = rule.getName();
    // 		    if (variables.containsKey(targetFieldName)) { //dont think it has a variable
    // 			targetFieldName = variables.get(targetFieldName);
    // 		    }
    // 		    out += sourceFieldName +  " -->" + targetFieldName + " : " + dependent.getName() + "\n";
    // 		    //out += sourceFieldName +  " -->" + targetFieldName + " : " + rule.getName() + "\n";
    // 		}
    // 	    }
    // 	}


    // 	for (StructureMapGroupRuleComponent r : rule.getRule()) {
    // 	    out += renderGroupArrowDetails(r,variables);
    // 	}

    // 	return out;
    // }
    
}
