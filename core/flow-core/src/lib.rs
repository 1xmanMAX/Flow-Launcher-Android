//! Nucleo del buscador universal: indice en memoria, busqueda difusa,
//! historial, calculadora, busquedas web, los archivos del movil
//! ([`archivos`]) y el host de los plugins de Flow
//! Launcher (`Executable`, `Executable_V2` y `JavaScript`, este con QuickJS
//! dentro del propio nucleo: ver [`plugins::js`]).
//!
//! No depende de Android: la app (Kotlin) le pasa lo que lee del sistema y
//! le pregunta por JNI ([`jni`]). Lo mismo servira para Mac e iOS.

pub mod archivos;
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
