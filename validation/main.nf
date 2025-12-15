#!/usr/bin/env nextflow

// Process to analyze input files
process analyzeData {

    publishDir path: { "${params.output_dir}/output/ron_meta_entities/file_name=${file}/" }, pattern: 'context_label=*/*',
            tags: {
                [metadata_path: "$file", metadata_test: "context_label2", "context_label": "context_label2"] + workflow.properties + task.properties
            }

    input:
    tuple path(file), val(config)

    output:
    path "*/*", emit: out_file
    stdout emit: bye

    script:
    """

    echo "Processing ${file}"
    cp ${file} a_${file}
    mkdir context_label=${config.dataset_id}
    cp -pr ${file} ./context_label=${config.dataset_id}/
    cp -pr a_${file} ./context_label=${config.dataset_id}/
    echo "bye1"
    """
}

// Process to upload results back to lakeFS
process uploadResults {
    publishDir "${params.output_dir}/output/ron_meta_entities/", tags: {
        [FOO: "${System.nanoTime()}", file: "$file"] + workflow.properties + task.properties
    }

    input:
    path result

    output:
    path "${result}"

    script:
    """
    # The publishDir directive handles the upload
    # No additional script needed
    echo "Results published to lakeFS"
    """
}

// Process to collect files into a directory and publish
process collectToDirectory {
    publishDir "${params.output_dir}/output/collected/", mode: 'copy', overwrite: true

    input:
    path input_files, stageAs: 'input_*/*'

    output:
    path "output_dir", emit: out_dir

    script:
    """
    mkdir -p output_dir
    cp -r input_* output_dir/
    """
}

// Example of direct interaction with lakeFS files in a process
process directLakeFSRead {
    input:
    val path

    output:
    stdout result_channel

    script:
    """
    # Use Nextflow's file function to access the lakeFS path
    cat \$(basename ${path})
    """
}

workflow {
    input_channel = Channel.fromPath(params.config_path + "/**/config.yaml", type: 'file')
            .map { new org.yaml.snakeyaml.Yaml().load(it) + [dataset_id: it.parent.name, file: it] }
            .map { it + [phenodata: { if (it.phenodata) it.phenodata.join(",") }] }
            .view()
//            .filter { !exclude_datasets.contains(it.dataset_id) }

    analyzeData(input_channel.map { tuple(it.file, it) })
    analyzeData.out.bye | view

    // Collect files from analyzeData into a directory and publish
    collectToDirectory(analyzeData.out.out_file.collect())
//    uploadResults(analyzeData.out)
//    directLakeFSRead('lakefs://infra-testing/main/output/meta_entity/meta_entity_dataset.parquet')
}

// Workflow completion handler
workflow.onComplete {
    println "Pipeline completed at: ${workflow.complete}"
    println "Execution status: ${workflow.success ? 'OK' : 'failed'}"
}