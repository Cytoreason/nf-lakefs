#!/usr/bin/env nextflow

// Process to analyze input files
process analyzeData {

    publishDir path: { "${params.output_dir}/output/ron_meta_entities/file_name=${file}/" }, pattern: 'context_label=*/*', tags: {
        [metadata_path: "${file}", metadata_test: "context_label2", "context_label": "context_label2"] + workflow.properties + task.properties
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
    publishDir { "${params.output_dir}/output/ron_meta_entities/" }, tags: {
        [FOO: "${System.nanoTime()}"] + workflow.properties + task.properties
    }

    input:
    path result

    output:
    path result

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
    stdout emit: result_channel

    script:
    """
    # Use Nextflow's file function to access the lakeFS path
    cat \$(basename ${path})
    """
}

workflow {
    input_channel = channel.fromPath(params.config_path + "/**/config.yaml", type: 'file')
        .map { f -> new org.yaml.snakeyaml.Yaml().load(f) + [dataset_id: f.parent.name, file: f] }
        .map { m -> m + [phenodata: {
            if (m.phenodata) {
                m.phenodata.join(",")
            }
        }] }
        .view()
    //            .filter { !exclude_datasets.contains(it.dataset_id) }

    analyzeData(input_channel.map { m -> tuple(m.file, m) })
    analyzeData.out.bye | view

    // Collect files from analyzeData into a directory and publish
    collectToDirectory(analyzeData.out.out_file.collect())
    //    uploadResults(analyzeData.out)
    //    directLakeFSRead('lakefs://infra-testing/main/output/meta_entity/meta_entity_dataset.parquet')
}
