package com.cabolabs.openehr.opt.model

@groovy.util.logging.Log4j
class OperationalTemplate {

   String uid
   String templateId
   String concept
   boolean isControlled = false

   // language is CODE_PHRASE, will be coded as terminology_id::code_string
   // http://en.wikipedia.org/wiki/List_of_ISO_639-1_codes
   String language // ISO_639-1::en


   // description
   String purpose // purpose language will be the same as the template language, so is not storeds
   List otherDetails = [] // List<Term>
   Term originalAuthor

   // Plays the role of a CComplexObject of aom
   ObjectNode definition // has attributes category, context and content of the COMPOSITION

   // Added to simplify path management
   // Paths will be calculated by a parser
   List paths = []

   Map nodes = [:] // TemplatePath -> ObjectNode (node) para pedir restricciones

   // RM attributes that are not in the OPT but also need to be indexed for querying.
   // This is like a schema, but is not including the attrs that are on OPTs.
   def rm_attributes_not_in_opt = [
     'COMPOSITION': [
       'context': 'EVENT_CONTEXT' // if no other_context is specified the event context is not on the OPT, we need to check if it is or not to avoid double indexing.
     ],
     'EVENT_CONTEXT': [
       'setting': 'DV_CODED_TEXT',
       'location': 'String',
       'start_time': 'DV_DATE_TIME',
       'end_time': 'DV_DATE_TIME'
     ],
     'ACTION': [
       'time': 'DV_DATE_TIME',
       'instruction_details': 'INSTRUCTION_DETAILS'
     ],
     'INSTRUCTION_DETAILS': [
       'instruction_id': 'LOCATABLE_REF',
       'activity_id': 'String'
     ],
     'INSTRUCTION': [
       'narrative': 'DV_TEXT',
       'expiry_time': 'DV_DATE_TIME'
     ],
     'ACTIVITY': [
       'timing': 'DV_PARSABLE',
       'action_archetype_id': 'String'
     ],
     'HISTORY': [
       'origin': 'DV_DATE_TIME',
       'period': 'DV_DURATION',
       'duration': 'DV_DURATION'
     ],
     'EVENT': [ // to avoid issues with cliens using abstract types, considered point event
       'time': 'DV_DATE_TIME'
     ],
     'POINT_EVENT': [
       'time': 'DV_DATE_TIME'
     ],
     'INTERVAL_EVENT': [
       'time': 'DV_DATE_TIME',
       'width': 'DV_DURATION'
     ],
     'ELEMENT': [
       'null_flavour': 'DV_CODED_TEXT'
     ]
   ]

   /*
    * gets a node by template path
    */
   Constraint getNode(String path)
   {
      return this.nodes[path]
   }

   boolean existsNodeByTemplateDataPath(String templateDataPath)
   {
      return this.nodes.find { it.value.templateDataPath == templateDataPath } != null
   }

   List getNodesByTemplateDataPath(String templateDataPath)
   {
      // .values return java.util.LinkedHashMap$LinkedValues not List
      def res = new ArrayList(this.nodes.findAll { it.value.templateDataPath == templateDataPath }.values())
      return res
   }

   def getTerm(String archetypeId, String nodeId)
   {
      return getFromOntology(archetypeId, nodeId, 'text')
   }

   def getDescription(String archetypeId, String nodeId)
   {
      return getFromOntology(archetypeId, nodeId, 'description')
   }

   def getLangCode()
   {
      this.language.split('::')[1]
   }
   def getLangTerminology()
   {
      this.language.split('::')[0]
   }

   private String getFromOntology(String archetypeId, String code, String part)
   {
      assert part == "text" || part == "description", "part should be text or description and is ${part}"

      // Recursive search for the archetype root by archetypeId
      def root = findRoot(archetypeId)

      if (!root)
      {
         //log.info( "root not found for "+ archetypeId +" "+ code )
         return
      }
      else
      {
         //log.info( "root found "+ root.toString() )
      }

      //println root.termDefinitions
      //root.termDefinitions.each{ println it.code }

      // Get the term from the ontology of the root archetype
      //println "root term definition codes: "+ root.termDefinitions.code

      def codedTerm = root.termDefinitions.find{ it.code == code }

      if (!codedTerm)
      {
         //log.info( "codedTerm not found "+ archetypeId +" "+ code )
         return
      }
      else
      {
         //log.info( "codedTerm found "+ codedTerm.code +" "+ codedTerm.term.text )
      }

      return codedTerm.term."${part}"
   }

   /**
    * Find root object node by archetypeId.
    */
   ObjectNode findRoot(String archetypeId)
   {
      return findRootRecursive(this.definition, archetypeId)
   }

   private ObjectNode findRootRecursive(ObjectNode obj, String archetypeId)
   {
      //log.info( "findRootRecursive aid="+ archetypeId +" o.aid="+ obj.archetypeId )
      if (obj.archetypeId == archetypeId) return obj // Stop recursion

      // can use any or find:
      // http://stackoverflow.com/questions/3049790/break-from-groovy-each-closure
      def root
      obj.attributes.any { attr ->

         root = findRootRecursive(attr, archetypeId)
         if (root) return true // any break, each does not break
      }
      return root
   }

   private ObjectNode findRootRecursive(AttributeNode attr, String archetypeId)
   {
      def root
      attr.children.any { obj ->

         root = findRootRecursive(obj, archetypeId)
         if (root) return true // any break, each does not break
      }
      return root
   }

   public List<ObjectNode> getReferencedArchetypes()
   {
      return getReferencedArchetypesRecursive(this.definition)
   }

   private List<ObjectNode> getReferencedArchetypesRecursive(ObjectNode obj)
   {
      List<ObjectNode> ret = []

      if (obj.path == '/' || obj.type == 'C_ARCHETYPE_ROOT')
      {
         ret << obj
      }

      obj.attributes.each { attr ->
         ret.addAll( getReferencedArchetypesRecursive(attr) )
      }

      return ret
   }

   private List<ObjectNode> getReferencedArchetypesRecursive(AttributeNode attr)
   {
      List<ObjectNode> ret = []

      attr.children.each { obj ->
         ret.addAll( getReferencedArchetypesRecursive(obj) )
      }

      return ret
   }

   /*
    * Adds attributes to the OPT that are defined in the RM but not in the OPT.
    * Some could be optional in the OPT, like COMPO.context will be in the OPT if
    * there is a constraint for other_context.
    */
   def complete()
   {
      completeRecursive(this.definition)
   }
   private completeRecursive(ObjectNode obn)
   {
      // attr name -> type
      Map rm_attrs = rm_attributes_not_in_opt[obn.rmTypeName]

      rm_attrs.each { attr, type ->

         // avoid if the attr is aready on the OPT
         if (!obn.attributes.find{ it.rmAttributeName == attr })
         {
            def aom_type = (type == 'String' ? 'C_PRIMITIVE_OBJECT' : 'C_COMPLEX_OBJECT')

            // avoid // on root paths
            def path_sep = "/"
            if (obn.path == "/") path_sep = ""

            def atn = new AttributeNode(
               rmAttributeName:  attr,
               type:             'C_SINGLE_ATTRIBUTE',
               parent:           obn,
               path:             obn.path +path_sep+ attr,
               dataPath:         obn.dataPath +path_sep+ attr,
               templatePath:     obn.templatePath +path_sep+ attr,
               templateDataPath: obn.templateDataPath +path_sep+ attr
               // TODO: cardinality
               // TODO: existence
            )

            def obnc = new ObjectNode(
               owner:      this,
               rmTypeName: type,
               type:       aom_type,
               templatePath: obn.templatePath +path_sep+ attr, // same paths as the attr since this has no nodeId
               path:         obn.path +path_sep+ attr,
               dataPath:     obn.dataPath +path_sep+ attr,
               templateDataPath: obn.templateDataPath +path_sep+ attr,
               parent:     atn
               // TODO: default_values
            )
            obnc.text = obnc.parent.parent.text +'.'+ obnc.parent.rmAttributeName
            obnc.description = obnc.parent.parent.description +'.'+ obnc.parent.rmAttributeName

            atn.children << obnc

            this.nodes[obnc.templatePath] = obnc

            obn.attributes << atn
         }
      }

      // recursion
      obn.attributes.each { atn ->
         completeRecursive(atn)
      }
   }
   private completeRecursive(AttributeNode atn)
   {
      atn.children.each { obn ->
         completeRecursive(obn)
      }
   }

}
