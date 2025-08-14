use std::process::Command;

fn main() {
    uniffi::generate_scaffolding("src/m3gif.udl").unwrap();
    
    // Generate Kotlin bindings after build
    println!("cargo:rerun-if-changed=src/m3gif.udl");
    
    // Run uniffi-bindgen to generate Kotlin bindings
    let output = Command::new("uniffi-bindgen")
        .args(&[
            "generate",
            "src/m3gif.udl",
            "--language", "kotlin",
            "--out-dir", "../../app/src/main/java"
        ])
        .output();
    
    match output {
        Ok(result) if result.status.success() => {
            println!("cargo:warning=Generated Kotlin bindings for m3gif");
        }
        Ok(result) => {
            eprintln!("Warning: Failed to generate Kotlin bindings: {}", 
                String::from_utf8_lossy(&result.stderr));
        }
        Err(e) => {
            eprintln!("Warning: Could not run uniffi-bindgen: {}", e);
        }
    }
}