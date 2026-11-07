//! Nucleo del buscador universal: indice en memoria, busqueda difusa,
//! historial, calculadora, busquedas web y el host de los plugins de Flow
//! Launcher (`Executable` y `Executable_V2`).
//!
//! No depende de Android: la app (Kotlin) le pasa lo que lee del sistema y
//! le pregunta por JNI ([`jni`]). Lo mismo servira para Mac e iOS.

pub mod busqueda_web;
pub mod calculadora;
pub mod coincidir;
pub mod historial;
pub mod indice;
pub mod motor;
pub mod normalizar;
pub mod plugins;
pub mod resultado;

mod jni;

pub use motor::Motor;
