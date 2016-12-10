package services.discovery.components.transformer

class Cedr_DotaceCastka2Rdf_Value extends SparqlUpdateTransformer {

    protected override val prefixes =
        """
          | PREFIX cedr: <http://cedropendata.mfcr.cz/c3lod/cedr/vocabCEDR#>
          | PREFIX dct: <http://purl.org/dc/terms/>
          | PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
          | PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
          | PREFIX lpviz: <http://visualization.linkedpipes.com/ontology/>
        """.stripMargin
    
    protected override val deleteClause =
        """
          |  ?prijemce cedr:obdrzelDotaci ?dotace .
          |  ?dotace cedr:castkaRozhodnuta ?castka .
        """.stripMargin
    
    protected override val insertClause =
        """
          |  ?prijemce lpviz:hasAbstraction ?abstraction .
          |
          |  ?abstraction rdf:value ?castka ;
          |	lpviz:unit "CZK" ;
          |	rdfs:label ?abstractionLabel .
        """.stripMargin
    
    protected override val whereClause =
        """
          |?prijemce cedr:obdrzelDotaci ?dotace .
          |  ?dotace cedr:castkaRozhodnuta ?castka .
          |
          |  OPTIONAL {
          |    ?dotace dct:title ?title .
          |	BIND(CONCAT("Allocated money for subsidy number ", STR(?title)) AS ?abstractionLabel)
          |  }
          |
          |  BIND(IRI(CONCAT(STR(?dotace), "/abstraction/cedr-dotace-castka2rdf-value")) AS ?abstraction)
        """.stripMargin
}
