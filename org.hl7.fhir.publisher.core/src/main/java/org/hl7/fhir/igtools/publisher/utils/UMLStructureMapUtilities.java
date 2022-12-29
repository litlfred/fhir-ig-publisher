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
		    getReferencedElementsBySource(rule,input.getName(),input.getName() + "." ,sources);
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
		    getReferencedElementsByTarget(rule,input.getName(),input.getName() + "." ,targets);
		}
	    }
	}
	return targets;
    }
    
    private String  renderSourceStructureDefinition(StructureDefinition sd, String alias, StructureMap map) {
	List<String> sources = new ArrayList<String>();
	for ( StructureMapGroupComponent group : map.getGroup()) {
	    log("Checking group:" + group.getName());
	    for ( StructureMapGroupInputComponent input : group.getInput()) {
		log("Checking Group Name(" + group.getName() + ") = Alias(" + ")");
		if (!input.getName().equals(alias)) {
		    continue;
		}
		for (StructureMapGroupRuleComponent rule : group.getRule()) {
		    getReferencedElementsByTarget(rule,input.getName(),input.getName() + "." ,sources);
		}		    

	    }
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


    private String  renderTargetStructureDefinition(StructureDefinition sd ,String alias, StructureMap map) {
	List<String> targets = new ArrayList<String>();
	for (  StructureMapGroupComponent group : map.getGroup()) {
	    for ( StructureMapGroupInputComponent input : group.getInput())  {
		if (!input.getName().equals(alias)) {
		    continue;
		}
		for (StructureMapGroupRuleComponent rule : group.getRule()) {
		    getReferencedElementsByTarget(rule,input.getName(),input.getName() + "." ,targets);
		}		    
	    }
	}

	String out = "class " + sd.getName() + " { " + "\n";	    
	StructureDefinitionSnapshotComponent snapshot = sd.getSnapshot();	
	for (ElementDefinition elem : snapshot.getElement()) {
	    ArrayList<String> types = new ArrayList<String>();
	    if (!targets.contains(elem.getPath())) {
		log("Skupping :" + elem.getPath() + " as not in " + String.join(",",targets));
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
	for (StructureMapGroupRuleTargetComponent target : rule.getTarget()) {
	    if ( !name.equals(target.getContext() ) ){
		continue;
	    }
	    String out = prefix + name;
            //	    if (rule.hasType() type) {
            //		out += " : " + type;
            //	    }
	    if (!rules.contains (out)) {
		rules.add( out);
	    }

	    for (StructureMapGroupRuleComponent r : rule.getRule()) {
		getReferencedElementsByTarget(r,name, prefix, rules );
	    }
	    for (StructureMapGroupRuleTargetComponent t : rule.getTarget()) {
		if (!t.hasElement() || name.equals(t.getContext() )
		    ) {
		    continue;
		}
		String element = t.getElement();
		String variable = t.getVariable();
		if (variable == null || variable.isBlank()) {
		    variable = name;
		}
		//String type = t.getType();
		for (StructureMapGroupRuleComponent r : rule.getRule()) {
		    getReferencedElementsByTarget(r, variable, prefix + element  + ".",rules);
		}
	    }
	}
	return rules;
    }


    private List<String> getReferencedElementsBySource(StructureMapGroupRuleComponent rule, String name) {
	List<String> rules = new ArrayList<String>();
	return getReferencedElementsBySource(rule,name,"",rules);
    }
    
    private List<String> getReferencedElementsBySource(StructureMapGroupRuleComponent rule, String name, String prefix, List<String> rules) {

	for (StructureMapGroupRuleSourceComponent source : rule.getSource()) {
	    if ( !name.equals(source.getContext() ) ){
		continue;
	    }
	    String out = prefix + name;
	    if (source.hasType()) {		
		out += " : " + source.getType();
	    }
	    if (!rules.contains (out)) {
		rules.add( out);
	    }
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
	    }
	} 
	return rules;
    }


    private String renderStructureMapUML(StructureMap map) {
	String sourceSD = null;
	String targetSD = null;
	String sourceAlias = null;
	String targetAlias = null;
	for (StructureMapStructureComponent input : map.getStructure()){	  
	    if (input.getMode() == StructureMapModelMode.TARGET) {
		log("found target");
		targetSD = input.getUrl();
		targetAlias = input.getAlias();		  		  
	    } else if (input.getMode() == StructureMapModelMode.SOURCE) {
		log("found source");
		sourceSD = input.getUrl();
		sourceAlias = input.getAlias();		  
	    }      
	}
	// List<String> sources = new ArrayList<String>();
	// for (StructureMapGroupRuleComponent rule : map.getRule()) {
	//     for (StructureMapGroupRuleComponent rule : map.getRule()) {
	//     sources.addAll(getReferencedElementsBySource(rule,rule.getName()));
	// }
	StructureDefinition source = (StructureDefinition) worker.fetchResource(StructureDefinition.class, sourceSD);
	StructureDefinition target = (StructureDefinition) worker.fetchResource(StructureDefinition.class, targetSD);
	    
	String out = "package \"" + map.getName() + "\" {" + "\n"
	    + "  namespace \"" + sourceSD + "\"\n{" + renderSourceStructureDefinition(source,sourceAlias,map) + "} " +  "\n"
	    + "  namespace \"" + map.getName() + "\" {\n" +  renderStructureMapGroups(map.getGroup()) + "\n} " + "\n"
	    + "  namespace \"" + targetSD + "\"\n{" + renderTargetStructureDefinition(target,targetAlias,map) + "\n} " + "\n"
	    + "} " + "\n";
	return out;
    }

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
    // // 		case StructureMapInputMode.TRANSLATE:
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
