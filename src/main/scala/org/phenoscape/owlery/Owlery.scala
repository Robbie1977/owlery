package org.phenoscape.owlery

import java.io.File

import scala.collection.JavaConverters._

import org.apache.commons.io.FileUtils
import org.semanticweb.HermiT.ReasonerFactory
import org.semanticweb.elk.owlapi.ElkReasonerFactory
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.io.FileDocumentSource
import org.semanticweb.owlapi.model.IRI
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration
import org.semanticweb.owlapi.model.OWLOntologyManager
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import uk.ac.manchester.cs.jfact.JFactFactory

object Owlery extends MarshallableOwlery {

  private[this] val factory = OWLManager.getOWLDataFactory
  private[this] val loaderConfig = new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
  val kbs = loadKnowledgebases(ConfigFactory.load().getConfigList("owlery.kbs").asScala.map(configToKBConfig).toSet)

  def kb(name: String): Option[Knowledgebase] = kbs.get(name)

  //  private[this] def checkForMissingImports(manager: OWLOntologyManager): Set[IRI] = {
  //    val allImportedOntologies = manager.getOntologies().flatMap(_.getImportsDeclarations).map(_.getIRI).toSet
  //    val allLoadedOntologies = for {
  //      ont <- manager.getOntologies()
  //      versionIRI <- Option(ont.getOntologyID.getOntologyIRI.get)
  //    } yield versionIRI
  //    allImportedOntologies -- allLoadedOntologies
  //  }

  private[this] def loadOntologyFromLocalFile(manager: OWLOntologyManager, file: File): Unit = manager.loadOntologyFromOntologyDocument(new FileDocumentSource(file), loaderConfig)

  private[this] def createOntologyFolderManager(): OWLOntologyManager = {
    val manager = OWLManager.createOWLOntologyManager
    manager.clearIRIMappers()
    manager.addIRIMapper(NullIRIMapper)
    manager
  }

  private[this] def importAll(manager: OWLOntologyManager): OWLOntology = {
    val axioms = for {
      ont <- manager.getOntologies().asScala
      axiom <- ont.getAxioms().asScala
    } yield axiom
    val newOnt = manager.createOntology
    manager.addAxioms(newOnt, axioms.asJava)
    newOnt
  }

  private[this] def configToKBConfig(config: Config) = KnowledgebaseConfig(config.getString("name"), config.getString("location"), config.getString("reasoner"))

  private[this] def loadKnowledgebases(configs: Set[KnowledgebaseConfig]): Map[String, Knowledgebase] =
    configs.map(loadKnowledgebase).map(kb => kb.name -> kb).toMap

  private[this] def loadKnowledgebase(config: KnowledgebaseConfig): Knowledgebase = {
    val ontology = if (config.location.startsWith("http")) loadOntologyFromWeb(config.location)
    else loadOntologyFromFolder(config.location)
    val reasoner = config.reasoner.toLowerCase match {
      case "structural" => new StructuralReasonerFactory().createReasoner(ontology)
      case "elk"        => new ElkReasonerFactory().createReasoner(ontology)
      case "hermit"     => new ReasonerFactory().createReasoner(ontology)
      case "jfact"      => new JFactFactory().createReasoner(ontology)
      case other        => throw new IllegalArgumentException(s"Invalid reasoner specified: $other")
    }
    Knowledgebase(config.name, reasoner)
  }

  private[this] def loadOntologyFromWeb(location: String): OWLOntology = {
    val manager = OWLManager.createOWLOntologyManager
    manager.loadOntology(IRI.create(location))
  }

  private[this] def loadOntologyFromFolder(location: String): OWLOntology = {
    val manager = createOntologyFolderManager()
    FileUtils.listFiles(new File(location), null, true).asScala.foreach(loadOntologyFromLocalFile(manager, _))
    val onts = manager.getOntologies().asScala
    if (onts.size == 1) onts.head
    else importAll(manager)
  }

  private[this] case class KnowledgebaseConfig(name: String, location: String, reasoner: String)

  private[this] object NullIRIMapper extends OWLOntologyIRIMapper {

    override def getDocumentIRI(iri: IRI): IRI = null

  }

}