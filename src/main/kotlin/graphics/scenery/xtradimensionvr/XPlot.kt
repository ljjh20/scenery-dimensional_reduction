package graphics.scenery.xtradimensionvr

import gnu.trove.set.hash.TIntHashSet
import graphics.scenery.*
import graphics.scenery.backends.Shaders
import graphics.scenery.utils.extensions.*
import graphics.scenery.volumes.Colormap
import hdf.hdf5lib.exceptions.HDF5SymbolTableException
import org.apache.commons.math3.distribution.HypergeometricDistribution
import org.apache.commons.math3.stat.descriptive.rank.Median
import org.apache.commons.math3.stat.inference.MannWhitneyUTest
import org.apache.commons.math3.stat.inference.TTest
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.set
import kotlin.math.*

/**.
 * cellxgene interactivity - start fixing selection and marking tools
 * jar python interaction (qt applet?)
 * get imgui working
 * billboard
 * most differentially expressed gene for each cluster
 * simplify controls
 *
 * serious performance hit from so many textboards? I observe around -5-7 fps
 * performance hit from toggling visibility of keys? many intersections and non-instanced nodes
 * instance key spheres? Instance textboards?
 * Textboard color off
 * must catch and be able to encode annotations that are not categoricals! Then will work for any dataset!
 *
 * proximity with invisible sphere .Intersect then show
 * VR controller example
 * camera show information
 *
 * @author Luke Hyman <lukejhyman@gmail.com>
 */

var geneNames = ArrayList<String>()
var geneExpr = ArrayList<FloatArray>()
var maxExprList = ArrayList<Int>()

class XPlot(filePath: String) : Node() {
    // a scaling factor for better scale relative to user
    private var positionScaling = 0.3f
    private val colormap = Colormap.get(encoding)

    val annFetcher =
        AnnotationsIngest(filePath)
    private val spatialCoords = annFetcher.umapReader3D()

    var annotationList = ArrayList<String>()

    // give annotations you would like (maybe with checkboxes, allow to enter the names of their annotations)
    // list of annotations
    init {
        for (ann in annFetcher.reader.getGroupMembers("/obs")) {
            try {
                val info = annFetcher.reader.getDataSetInformation("/uns/" + ann + "_categorical")
                if (info.toString().toCharArray().size < 17) {
                    annotationList.add(ann)
                }
            } catch (e: HDF5SymbolTableException) {
                logger.info("$ann is not color encodable and will exist only as metadata")
            }
        }
        println(annotationList)
    }

    private var annotationArray = ArrayList<FloatArray>() //used to color spheres, normalized
    private val rawAnnotations = ArrayList<ArrayList<*>>()
    private val typeList =
        ArrayList<String>() //grow list of annotation datatypes (used currently for metadata check for labels)

    val annKeyList = ArrayList<Mesh>()
    val labelList = ArrayList<Mesh>()
    private val rgbColorSpectrum = Colormap.get("jet")

    init {
        for (ann in annotationList) {

            annotationArray.add(run {
                val raw = annFetcher.h5adAnnotationReader("/obs/$ann", false)

                rawAnnotations.add(raw) // used to attach metadata spheres
                val norm = FloatArray(raw.size) // array of zeros if annotation entries are all the same

                when (raw[0]) {
                    is Byte -> {
                        typeList.add("Byte")
                        val max: Byte? = (raw as ArrayList<Byte>).maxOrNull()
                        if (max != null && max > 0f) {
                            for (i in raw.indices)
                                norm[i] = (raw[i]).toFloat() / max
                        }
                    }
                    is Short -> {
                        typeList.add("Short")
                        val max: Short? = (raw as ArrayList<Short>).maxOrNull()
                        if (max != null && max > 0f) {
                            for (i in raw.indices)
                                norm[i] = (raw[i] as Short).toFloat() / max
                        }
                    }
                }
                norm
            })
            annKeyList.add(createSphereKey(ann))
        }
    }

    //generate master spheres for every 10k cells for performance
    private val masterSplit = 10_000
    private val masterCount = ceil(spatialCoords.size.toFloat() / masterSplit).toInt()
    val masterMap = hashMapOf<Int, Icosphere>()

    init {
        val buffer = annFetcher.fetchGeneExpression()
        geneNames = buffer.first
        geneExpr = buffer.second
        maxExprList = buffer.third  // max of each gene from normalization - used by color map label

        loadDataset()
        updateInstancingArrays()
        updateInstancingLambdas()
        annKeyList[0].visible = true
    }

    private fun loadDataset() {

        // hashmap to emulate at run time variable declaration
        // allows for dynamically growing number of master spheres with size of dataset
        for (i in 1..masterCount) {
            val masterTemp = Icosphere(0.018f * positionScaling, 1) // sphere properties
            masterMap[i] = addMasterProperties(masterTemp, i)
        }
        logger.info("hashmap looks like: $masterMap")

        //create and add instances using their UMAP coordinates as position
        var resettingCounter = 0
        var parentIterator = 1

        val center = fetchCenterOfMass(spatialCoords)

        for ((counter, coord) in spatialCoords.withIndex()) {
            if (resettingCounter >= masterSplit) {
                parentIterator++
                logger.info("parentIterator: $parentIterator")
                resettingCounter = 0
            }

            val s = Icosphere(0.02f * positionScaling, 1) // add as icosphere so intersection works

            for ((annCount, annotation) in annotationList.withIndex())  //add all annotations as metadata (for label center of mass)
                s.metadata[annotation] = rawAnnotations[annCount][counter]

//            for ((annCount, annotation) in metaOnlyAnnList.withIndex())
//                s.metadata[annotation] = metaOnlyRawAnnotations[annCount][counter]
            if (s.metadata["cell_ontology_class"] == 3.toByte()) {
                s.metadata["selected"] = true
            }

            s.metadata["index"] = counter  // used to identify row of the cell
            s.parent = masterMap[parentIterator]
            s.position =
                (Vector3f(
                    (coord[0] - center[0]),
                    (coord[1] - center[1] + 10f),
                    (coord[2] - center[2])
                )) * positionScaling
            s.instancedProperties["ModelMatrix"] = { s.world }
            masterMap[parentIterator]?.instances?.add(s)
            resettingCounter++
        }
        addChild(dotMesh)

//      create labels for each annotation
        for ((typeCount, annotation) in annotationList.withIndex()) {
            labelList.add(generateLabels(annotation, typeList[typeCount]))
        }
        addChild(textBoardMesh)
    }

    /**
     * updateInstancingArrays creates new data arrays for each instance
     * only needs to be called at launch or when new data such as gene expression needs to be loaded
     */
    fun updateInstancingArrays() {
        var resettingCounter = 0
        var parentIterator = 1
        for (i in spatialCoords.indices) {
            if (resettingCounter >= masterSplit) {
                parentIterator++
                resettingCounter = 0
            }
            val indexedGeneExpression = ArrayList<Float>()
            val indexedAnnotations = ArrayList<Float>()

            // index element counter of every array of gene expressions and add to new ArrayList
            for (gene in geneExpr)
                indexedGeneExpression += gene[i]

            for (annotation in annotationArray)
                indexedAnnotations += annotation[i]

            val s = masterMap[parentIterator]!!.instances[resettingCounter]
            s.metadata["colors"] = arrayOf(  // fix current state as metadata to avoid race conditions
                indexedGeneExpression,
                indexedAnnotations
            )

            resettingCounter++
        }
        for (master in 1..masterCount) {
            (masterMap[master]?.metadata?.get("MaxInstanceUpdateCount") as AtomicInteger).getAndIncrement()
        }
    }

    /**
     * updates the color instancing property of each point with the current index of the data arrays
     */
    fun updateInstancingLambdas() {

        var resettingCounter = 0
        var parentIterator = 1

        for (i in spatialCoords.indices) {

            if (resettingCounter >= masterSplit) {
                parentIterator++
                resettingCounter = 0
            }
            val s = masterMap[parentIterator]!!.instances[resettingCounter]

            s.instancedProperties["Color"] = {
                when {
                    s.metadata["selected"] == true -> s.material.diffuse.xyzw()

                    annotationMode -> rgbColorSpectrum.sample((s.metadata["colors"] as Array<ArrayList<Float>>)[1][annotationPicker])

                    !annotationMode -> colormap.sample((s.metadata["colors"] as Array<ArrayList<Float>>)[0][genePicker] / 10)

                    else -> Vector4f(1f, 0f, 0f, 1f)

                }
            }
            resettingCounter++
        }
        for (master in 1..masterCount) {
            (masterMap[master]?.metadata?.get("MaxInstanceUpdateCount") as AtomicInteger).getAndIncrement()
        }
    }

    private fun generateLabels(annotation: String, type: String): Mesh {
        logger.info("label being made")

        val m = Mesh()
        val mapping = annFetcher.h5adAnnotationReader("/uns/" + annotation + "_categorical") as ArrayList<String>

        val mapSize = if (mapping.size > 1) mapping.size - 1 else 1

        for ((count, label) in mapping.withIndex()) {
            val t = TextBoard()

            t.text = label.replace("ï", "i")
            t.transparent = 0
            t.fontColor = Vector3f(0f, 0f, 0f).xyzw()
            t.backgroundColor = rgbColorSpectrum.sample(count.toFloat() / mapSize)

            when (type) {
                "Byte" -> t.position = fetchCellLabelPosition(annotation, count.toByte())
                "Short" -> t.position = fetchCellLabelPosition(annotation, count.toShort())
            }

            t.scale = Vector3f(0.3f, 0.3f, 0.3f) * positionScaling

            t.addChild(Sphere(0.1f, 1))
            m.addChild(t)
        }

        m.visible = false
        textBoardMesh.addChild(m)
        return m
    }

    // for a given cell type, find the average position for all of its instances. Used to place label in sensible position, given that the data is clustered
    private fun fetchCellLabelPosition(annotation: String, type: Any): Vector3f {
        val additiveMass = FloatArray(3)
        var filteredLength = 0f

        for (master in 1..masterCount) {
            for (instance in masterMap[master]!!.instances.filter { it.metadata[annotation] == type }) {
                additiveMass[0] += instance.position.toFloatArray()[0]
                additiveMass[1] += instance.position.toFloatArray()[1]
                additiveMass[2] += instance.position.toFloatArray()[2]

                filteredLength++
            }
        }
        return Vector3f(
            (additiveMass[0] / filteredLength),
            (additiveMass[1] / filteredLength),
            (additiveMass[2] / filteredLength)
        )
    }

    private fun fetchCenterOfMass(coordArray: ArrayList<ArrayList<Float>>): Vector3f {
        val additiveMass = FloatArray(3)
        var filteredLength = 0

        for (entry in coordArray) {
            for (axis in 0..2) {
                additiveMass[axis] += entry[axis]
            }
            filteredLength++
        }
        return Vector3f(
            (additiveMass[0] / filteredLength),
            (additiveMass[1] / filteredLength),
            (additiveMass[2] / filteredLength)
        )
    }

    private fun createSphereKey(annotation: String): Mesh {
        val m = Mesh()
        val mapping = annFetcher.h5adAnnotationReader("/uns/" + annotation + "_categorical")

        val rootPosY = 10f
        val rootPosX = -5.5f
        val scale = 0.6f

        val sizeList = arrayListOf<Int>()
        val overflowLim = (22 / scale).toInt()
        var overflow = 0
        var maxString = 0

        for (cat in mapping) {

            if (overflow < overflowLim) {
                val len = cat.toString().toCharArray().size
                if (len > maxString)
                    maxString = len
                overflow++

            } else {
                if (maxString < scale * 70)
                    sizeList.add(maxString)
                else
                    sizeList.add((scale * 70).toInt())
                maxString = 0
                overflow = 0
            }
        }

        val title = TextBoard()
        title.transparent = 1
        title.text = annotation
        title.scale = Vector3f(scale * 2)
        title.fontColor = Vector3f(180 / 255f, 23 / 255f, 52 / 255f).xyzw()
        title.position = Vector3f(rootPosX + scale, rootPosY, -11f)
        m.addChild(title)

        overflow = 0
        var lenIndex = -1 // -1 so first column isn't shifted
        var charSum = 0
        val mapSize = if (mapping.size > 1) mapping.size - 1 else 1

        for ((colorIncrement, cat) in mapping.withIndex()) {

            val key = TextBoard()
            val tooLargeBy = cat.toString().toCharArray().size - (scale * 70)

            when {
                (tooLargeBy >= 0) ->
                    key.text = cat.toString().replace("ï", "i").dropLast(tooLargeBy.toInt() + 5) + "..."// not utf-8 -_-
                (tooLargeBy < 0) ->
                    key.text = cat.toString().replace("ï", "i") // not utf-8 -_-
            }

            key.fontColor = Vector3f(1f, 1f, 1f).xyzw()
            key.transparent = 1
            key.scale = Vector3f(scale)

            val sphere = Icosphere(scale / 2, 3)
            sphere.material.diffuse = rgbColorSpectrum.sample(colorIncrement.toFloat() / mapSize).xyz()
            sphere.material.ambient = Vector3f(0.3f, 0.3f, 0.3f)
            sphere.material.specular = Vector3f(0.1f, 0.1f, 0.1f)
            sphere.material.roughness = 0.19f
            sphere.material.metallic = 0.0001f

            if (lenIndex == -1) {
                key.position = Vector3f(rootPosX + scale, rootPosY - (overflow + 1) * scale, -11f)
                sphere.position = Vector3f(rootPosX, (rootPosY - (overflow + 1) * scale) + scale / 2, -11f)

            } else {
                key.position =
                    Vector3f(rootPosX + scale + (charSum * 0.31f * scale), rootPosY - (overflow + 1) * scale, -11f)
                sphere.position = Vector3f(
                    rootPosX + (charSum * 0.31f * scale),
                    (rootPosY - (overflow + 1) * scale) + scale / 2,
                    -11f
                )
            }
            m.addChild(sphere)
            m.addChild(key)
            overflow++

            if (overflow == overflowLim && sizeList.size > 0) { // also checking for case of mapping.size == overFlowLim
                lenIndex++
                overflow = 0
                charSum += if (sizeList[lenIndex] < 12) 18
                else sizeList[lenIndex]

            }
        }
        m.visible = false
        addChild(m)
        return m
    }

    private fun addMasterProperties(master: Icosphere, masterNumber: Int): Icosphere {
        /*
       Generate an Icosphere used as Master for 10k instances.
       Override shader to allow for varied color on instancing and makes color an instanced property.
        */
        master.metadata["MaxInstanceUpdateCount"] = AtomicInteger(1)
//        (metadata["MaxInstanceUpdateCount"] as? AtomicInteger)?.getAndIncrement()
        master.name = "master$masterNumber"
        master.material = ShaderMaterial(
            Shaders.ShadersFromFiles(
                arrayOf(
                    "DefaultDeferredInstanced.frag",
                    "DefaultDeferredInstancedColor.vert"
                ), XPlot::class.java
            )
        ) //overrides the shader
        master.material.ambient = Vector3f(0.3f, 0.3f, 0.3f)
        master.material.specular = Vector3f(0.1f, 0.1f, 0.1f)
//        master.material.roughness = 0.6f
//        master.material.metallic = 0.8f //0.0001f
        master.instancedProperties["ModelMatrix"] = { master.world }
        master.instancedProperties["Color"] = { master.material.diffuse.xyzw() }

        dotMesh.addChild(master)

        return master
    }

    fun hypergeometricTest(selectedCells: TIntHashSet, backgroundCells: TIntHashSet): ArrayList<Int> {
        /**
         * could filter out genes with more than 80% zeros
         * return list of strings of 10 most highly expressed genes in the selection relative to background expression.
         * if no cells are selected, all p values are zero - fold change is always zero as selected is zero -> empty gene list
         */
        val logRatioMap = HashMap<Int, Double>()

        for (geneIndex in 0 until annFetcher.numGenes) {

            val expression = annFetcher.cscReader(geneIndex)
            val nonzeroExpression = TIntHashSet()

            for ((cell, expr) in expression.withIndex()) {
                when {
                    expr > 0 -> nonzeroExpression.add(cell)
                }
            }

            val intersection = TIntHashSet(selectedCells)
            intersection.retainAll(nonzeroExpression)  //intersection of selected cells and cells expressing the gene

            if ((1 - HypergeometricDistribution(
                    null,
                    annFetcher.numCells,
                    nonzeroExpression.size(),
                    selectedCells.size()
                ).cumulativeProbability(intersection.size())).toFloat() <= 0.05f
            ) {
                val selectedExpressions = ArrayList<Double>()
                val backgroundExpressions = ArrayList<Double>()

                for (cell in selectedCells) {
                    selectedExpressions.add(expression[cell].toDouble())
                }
                for (cell in backgroundCells) {
                    backgroundExpressions.add(expression[cell].toDouble())
                }
                val foldChange =
                    abs(
                        log2(
                            Median().evaluate(selectedExpressions.toDoubleArray()) / Median().evaluate(
                                backgroundExpressions.toDoubleArray()
                            )
                        )
                    )
                if (foldChange > 0.7) {
                    logRatioMap[geneIndex] = foldChange
                }
            }
        }

        val maxGenesList = ArrayList<Int>()
        for (i in 0..10) {
            val maxKey = logRatioMap.maxByOrNull { it.value }?.key
            if (maxKey != null) {
                maxGenesList.add(maxKey)
            }
            logRatioMap.remove(maxKey)
        }

        return maxGenesList
    }

    fun welchTTest(selectedCells: ArrayList<Int>, backgroundCells: ArrayList<Int>): ArrayList<Int> {
        // looking for biggest t, ie the most significant difference in the two distributions
        val pMap = HashMap<Int, Double>()
        val maxGenesList = ArrayList<Int>()

        for (geneIndex in annFetcher.nonZeroGenes) {
            val expression = annFetcher.cscReader(geneIndex)

            val selectedArray = ArrayList<Double>()
            val backgroundArray = ArrayList<Double>()

            selectedCells.forEach { selectedArray.add(expression[it].toDouble()) }

            backgroundCells.forEach { backgroundArray.add(expression[it].toDouble()) }

            pMap[geneIndex] =
                MannWhitneyUTest().mannWhitneyUTest(selectedArray.toDoubleArray(), backgroundArray.toDoubleArray())
        }

        for (i in 0..9) {
            val maxKey = pMap.minByOrNull { it.value }?.key
            if (maxKey != null) {
                maxGenesList.add(maxKey)
            }
            pMap.remove(maxKey)
        }

        return maxGenesList

//        TTest().tTest(selectedArray.toDoubleArray(), backgroundArray.toDoubleArray())
    }

    fun maxDiffExpressedGenes(
        selectedCells: ArrayList<Int>,
        backgroundCells: ArrayList<Int>,
        method: String = "Mann"
    ): ArrayList<Int> {
        // looking for biggest t, ie the most significant difference in the two distributions
        val pMap = HashMap<Int, Double>()
        val maxGenesList = ArrayList<Int>()

        for (geneIndex in annFetcher.nonZeroGenes) {
            val expression = annFetcher.cscReader(geneIndex)

            val selectedArray = ArrayList<Double>()
            val backgroundArray = ArrayList<Double>()

            selectedCells.forEach { selectedArray.add(expression[it].toDouble()) }

            backgroundCells.forEach { backgroundArray.add(expression[it].toDouble()) }

            pMap[geneIndex] = if (method == "TTest") TTest().tTest(
                selectedArray.toDoubleArray(),
                backgroundArray.toDoubleArray()
            )
            else MannWhitneyUTest().mannWhitneyUTest(
                selectedArray.toDoubleArray(),
                backgroundArray.toDoubleArray()
            )
        }

        for (i in 0..9) {
            val maxKey = pMap.minByOrNull { it.value }?.key
            if (maxKey != null) {
                maxGenesList.add(maxKey)
            }
            pMap.remove(maxKey)
        }

        return maxGenesList
    }

}
