package org.hl7.fhir.igtools.publisher;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public Map<String,String> renderUML(StructureMap map) throws FHIRException  {
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



    private String renderStructureMapUML(StructureMap map) throws FHIRException {
	//maps are variable name to element path
	Map<String,String> svars = new TreeMap<String,String>();
	Map<String,String> tvars = new TreeMap<String,String>();
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
		    tvars.put(input.getName(),target.getName()); //or .getType() or getTypeName()?
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
		    svars.put(input.getName(),source.getName()); //or .getType() or getTypeName()?
		}		break;
	    }      
	}
	log("done matching first group against structure");
	log(" source SD ftype " + source.fhirType());
	log(" source SD gettype " + source.getType());
	log(" source SD gettypename " + source.getTypeName());
	log(" source SD getname " + source.getName());
	log(" target SD ftype " + target.fhirType());
	log(" target SD gettype " + target.getType());
	log(" target SD gettypename " + target.getTypeName());
	log(" target SD getname " + target.getName());

	if (source == null) {
	    throw new FHIRException("Could not find source when generating UML for structure map:" + map.getName());
	}
	if (target == null) {
	    throw new FHIRException("Could not find target when generating UML for structure map:" + map.getName());
	}
	
	String sout = "";
	for (String s: svars.keySet()) {
	    sout += "\n     " + s + " -> " + svars.get(s) ;
	}
	log("Starting Source Vars=" + sout);
	String tout = "";
	for (String t: tvars.keySet()) {
	    tout += "\n     " + t + " -> " + tvars.get(t) ;
	}
	log("Starting target Vars=" + tout);

	
	List<String> arrows = new ArrayList<String>();	
	for (StructureMapGroupRuleComponent rule : group.getRule()) {
	    log("looking for arrows under top level rule: \"" + rule.getName() + "\"");
	    calculateArrows(rule,svars,tvars,arrows);
	}

	log("Found " + arrows.size() + " arrows");
	String sourceClassUML = "    class " + source.getName() + " {\n      " + String.join("\n      ",svars.values().toArray(new String[svars.values().size()])) + "\n    }\n";
	String targetClassUML = "    class " + target.getName() + " {\n      " + String.join("\n      ",tvars.values().toArray(new String[tvars.values().size()])) + "\n    }\n";

	String out = "package \"" + map.getName() + "\" {" + "\n"
	    + "  namespace \"" + source.getName() + "\" {\n" + sourceClassUML + "\n  }\n"
//	    + "  namespace \"" + map.getName() + "\" {\n" +  renderStructureMapGroups(map.getGroup()) + "\n} " + "\n"
	    + "  namespace \"" + target.getName() + "\" {\n" + targetClassUML + "\n  }\n"
	    + "  " + String.join("\n  ",arrows)  + "\n"
	    + "} " + "\n";
	return out;    
    }

    private void calculateArrows(StructureMapGroupRuleComponent rule, Map<String,String> svars, Map<String,String> tvars,List<String> arrows) {
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
	    svars.put(source.getVariable(),svars.get(source.getContext()) + "." + source.getElement());
	}
	
	for (StructureMapGroupRuleTargetComponent target : rule.getTarget()) {
	    if  ( ! target.hasContext()
		  || ! target.hasElement()		  
		  || ! tvars.containsKey(target.getContext())
		) {
		continue;
	    }
	    log("    found context (" + target.getContext() + ") in rule for " + tvars.get(target.getContext()) + " on target element " + target.getElement() );
	    if (target.hasVariable()) {
		log("    Adding source var " + target.getVariable() + "-> " + tvars.get(target.getContext()) + "." + target.getElement());
		tvars.put(target.getVariable(),tvars.get(target.getContext()) + "." + target.getElement());
	    }
	    if (!target.hasTransform() ) {
		continue;
	    }
	    log("    Found target var " + target.getContext() + "." + target.getElement() + " with tranform " + target.getTransform());
	    String targetVar = tvars.get(target.getContext()) + "." + target.getElement();
	    if (!tvars.containsValue(targetVar)) {
		tvars.put(targetVar,targetVar);
	    }
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
		    arrows.add(svars.get(idType.asStringValue()) +  " --> " + targetVar + " : " + rule.getName() );
		} else if (sourceParam.hasValueStringType()) {
		    log("       found string value type");
		    StringType stringType =  sourceParam.getValueStringType();
		    String stringVal = stringType.asStringValue();
		    String qStringVal = "\"\"" + stringVal + "\"\"";
		    log("      found source string for transform =" + stringVal);
		    // we want to indicate a constant value is mapped to the target
		    arrows.add(qStringVal +  " --> " + targetVar + " : " + rule.getName() );
		    svars.put(qStringVal,qStringVal); //make sure string value shows up in class UML definition
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
	if (rule.hasDependent()) {
	    log("found dependants - need to add arrows");
	    //todo: do this 
	}
	for (StructureMapGroupRuleComponent r: rule.getRule()) {
	    log("Walking down rule \"" + r.getName() + "\"");
	    calculateArrows(r,svars,tvars,arrows);
	}
    }
    
	
    
}
