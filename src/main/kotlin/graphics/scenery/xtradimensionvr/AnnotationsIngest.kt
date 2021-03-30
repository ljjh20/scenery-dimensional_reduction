package graphics.scenery.xtradimensionvr

import ch.systemsx.cisd.hdf5.HDF5Factory
import graphics.scenery.numerics.Random
import hdf.hdf5lib.exceptions.HDF5SymbolTableException
import java.lang.IllegalArgumentException
import java.util.*
import kotlin.collections.ArrayList

class AnnotationsIngest {
    private val h5adPath = "/home/luke/PycharmProjects/VRCaller/file_conversion/mammary_gland_vr_processed.h5ad"

    private val intTypeArray = arrayOf("n_genes", "louvain", "leiden", "n_cells", "dispersions_norm")
    private val floatTypeArray = arrayOf("n_counts", "means", "dispersions")

    init{

    }

    fun fetchTriple(nameOutput:ArrayList<String>, geneNames: List<String> = listOf("Alg12", "Asf1b", "Cd3e", "Fbxo21", "Gm15800")): Triple<ArrayList<ArrayList<Float>>, ArrayList<Any>, ArrayList<Int>>{
        val nameReader = nastyh5adAnnotationReader("/var/index")
        val geneIndexList = ArrayList<Int>()

//        for(i in geneNames){
//            nameOutput.add(i)
//            geneIndexList.add(nameReader.indexOf(i))
//        }

        val randGeneList = ArrayList<String>()
        for(i in 0..20){
            randGeneList.add(nameReader[Random.randomFromRange(0f, nameReader.size.toFloat()).toInt()] as String)
        }
        for(i in randGeneList){
            nameOutput.add(i)
            geneIndexList.add(nameReader.indexOf(i))
        }

        return Triple(UMAPReader3D(), h5adAnnotationReader("/obs/cell_ontology_class"), geneIndexList)
    }

    fun UMAPReader3D(): ArrayList<ArrayList<Float>>{

        val reader = HDF5Factory.openForReading(h5adPath)
        val UMAP = arrayListOf<ArrayList<Float>>()

        var tripletCounter = 0
        val cellUMAP = ArrayList<Float>()

        for(coordinate in reader.float32().readArray("/obsm/X_umap")){
            if(tripletCounter < 3){
                cellUMAP.add(coordinate)
                tripletCounter += 1
            }
            else {
                UMAP.add(arrayListOf(cellUMAP[0], cellUMAP[1], cellUMAP[2])) // actual values instead of pointer to object
                cellUMAP.clear()
                tripletCounter = 1 // zero for first loop, then 1, as first entry is added in else clause
                cellUMAP.add(coordinate)
            }
        }
        UMAP.add(arrayListOf(cellUMAP[0], cellUMAP[1], cellUMAP[2])) // add final sub-array
        return UMAP
    }

    fun nastyh5adAnnotationReader(pathLike: String): ArrayList<Any>{
        /**
         * path-like input of the form </obs or /var>/<annotation>
         */
        val reader = HDF5Factory.openForReading(h5adPath)
        val annType: String = pathLike.slice(1 until 4) // returns either 'var' or 'obs'
        val annotation = pathLike.substring(5) // returns just the annotation requested

        // check if inputs are valid for this dataset
        try {
            reader.getDataSetInformation(pathLike)

        } catch (e: HDF5SymbolTableException){ // Exception raised when dataset doesn't exist (H5E_SYM)
            throw IllegalArgumentException("the dataset $pathLike doesn't exist")
        }

        if((annType != "obs") && (annType != "var")) {
            throw IllegalArgumentException("annType must be either 'var' or 'obs'")
        }

        // initialize arrays
        val annotationArray = ArrayList<Any>()
        val categoryMap = hashMapOf<Int, String>()

        // try-catch to check if dataset is mapped to a categorical, extracting with corresponding mapping if so
        try {
            var entryCounter = 0

            for (category in reader.string().readArray("/uns/" + annotation + "_categorical")) {
                categoryMap[entryCounter] = category
                entryCounter += 1
            }

            for(i in reader.int8().readArray(pathLike)) {
                categoryMap[i.toInt()]?.let { annotationArray.add(it) }
            }

        } catch(e: HDF5SymbolTableException){
            when {
                intTypeArray.contains(annotation) -> for (i in reader.int32().readArray(pathLike)) {
                    annotationArray.add(i) }
                floatTypeArray.contains(annotation) -> for (i in reader.float32().readArray(pathLike)) {
                    annotationArray.add(i) }
                else -> for (i in reader.string().readArray(pathLike)) {
                    annotationArray.add(i) }
            }
        }
        return annotationArray
    }

    fun h5adAnnotationReader(hdfPath: String): ArrayList<Any>{
        /**
         * reads any 1 dimensional annotation (ie obs, var, uns), checking if a categorical map exists for them
         **/
        if(hdfPath[4].toString() != "/"){
            throw InputMismatchException("this function is only for reading obs, var, and uns")
            }


        val reader = HDF5Factory.openForReading(h5adPath)
        val data = ArrayList<Any>()
        val categoryMap = hashMapOf<Int, String>()
        val annotation = hdfPath.substring(5) // returns just the annotation requested

        when {
            reader.getDataSetInformation(hdfPath).toString().contains("STRING") ->
                for(i in reader.string().readArray(hdfPath)) { data.add(i) }

            reader.getDataSetInformation(hdfPath).toString().contains("INTEGER(1)") ->
                try {
                    var entryCounter = 0

                    for (category in reader.string().readArray("/uns/" + annotation + "_categorical")) {
                        categoryMap[entryCounter] = category
                        entryCounter += 1
                    }

                    for(i in reader.int8().readArray(hdfPath)) { categoryMap[i.toInt()]?.let { data.add(it) } }

                } catch(e: HDF5SymbolTableException) { // int8 but not mapped to categorical
                    for(i in reader.int8().readArray(hdfPath)) { categoryMap[i.toInt()]?.let { data.add(it) } }
                }
            reader.getDataSetInformation(hdfPath).toString().contains("INTEGER(2)") ->
                    for(i in reader.int16().readArray(hdfPath)) { data.add(i) }

            reader.getDataSetInformation(hdfPath).toString().contains("INTEGER(4)") ->
                for(i in reader.int32().readArray(hdfPath)) { data.add(i) }

            reader.getDataSetInformation(hdfPath).toString().contains("INTEGER(8)") ->
                for(i in reader.int64().readArray(hdfPath)) { data.add(i) }

            reader.getDataSetInformation(hdfPath).toString().contains("FLOAT(4)") ->
                for(i in reader.float32().readArray(hdfPath)) { data.add(i) }

            reader.getDataSetInformation(hdfPath).toString().contains("FLOAT(8)") ->
                for(i in reader.float64().readArray(hdfPath)) { data.add(i) }

            reader.getDataSetInformation(hdfPath).toString().contains("BOOLEAN") ->
                for(i in reader.int8().readArray(hdfPath)) { data.add(i) }
        }
        return data
    }
}

fun main(){
    AnnotationsIngest()
}
