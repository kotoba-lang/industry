const fs = require('fs');
const path = require('path');

const searchIndexPath = 'target/doc/search-index.js';
const outputPath = 'gftd.jsonl';

// 1. Read and parse search-index.js
const content = fs.readFileSync(searchIndexPath, 'utf8');
// Extract the JSON part from the JSONP structure
const jsonpMatch = content.match(/^searchIndex\((.*)\)$/s);
if (!jsonpMatch) {
    console.error("Could not parse search-index.js");
    process.exit(1);
}
const searchIndex = JSON.parse(jsonpMatch[1]);

// 2. Build module hierarchy for each crate
const moduleGraph = {
    dimension: 'module_structure',
    type: 'hierarchical_graph',
    crates: {},
};

Object.entries(searchIndex).forEach(([crateName, crateData]) => {
    moduleGraph.crates[crateName] = [];
    const modules = {};

    crateData.forEach(item => {
        if (item.ty === 15) { // Type 15 corresponds to 'Module'
            const pathParts = item.p.split('::');
            // Iterate over the path to build the hierarchy
            for (let i = 0; i < pathParts.length; i++) {
                const currentPath = [...pathParts.slice(0, i), item.n].join('::');
                if (!modules[currentPath]) {
                    const module = {
                        id: `${crateName}::${currentPath}`,
                        path: `${crateName}::${currentPath}`,
                        name: i === pathParts.length - 1 ? item.n : pathParts[i+1],
                        parent: pathParts.slice(0,i).length > 0 ? `${crateName}::${pathParts.slice(0,i).join('::')}` : crateName,
                    };
                    moduleGraph.crates[crateName].push(module);
                    modules[currentPath] = module;
                }
            }
        }
    });
});

// 3. Append to gftd.jsonl
fs.appendFileSync(outputPath, JSON.stringify(moduleGraph) + '\n', 'utf8');

console.log(`Successfully appended module structure analysis to ${outputPath}`); 