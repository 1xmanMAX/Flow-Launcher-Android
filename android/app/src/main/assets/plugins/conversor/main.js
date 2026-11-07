// Conversor de unidades, sin conexion. Ejemplo de plugin de JavaScript
// (ver core/flow-core/src/plugins/js.rs para la API).
//
//   cv 10 km a mi      -> 6.213712 mi
//   cv 100 f c         -> 37.77778 °C
//   cv 5 kg            -> en g, lb, oz...
//   cv 2,5 tazas en ml -> 591.4706 ml

// [simbolo, factor a la unidad base, nombre en plural, alias...]. La
// temperatura no es proporcional: va aparte.
var TABLA = {
  longitud: [
    ['mm', 0.001, 'milímetros', 'milimetro'],
    ['cm', 0.01, 'centímetros', 'centimetro'],
    ['m', 1, 'metros', 'metro', 'meter', 'metre', 'mts', 'mt'],
    ['km', 1000, 'kilómetros', 'kilometro', 'kilometer', 'kms'],
    ['in', 0.0254, 'pulgadas', 'pulgada', 'inch', 'inches', 'pulg', '"'],
    ['ft', 0.3048, 'pies', 'pie', 'foot', 'feet', "'"],
    ['yd', 0.9144, 'yardas', 'yarda', 'yard'],
    ['mi', 1609.344, 'millas', 'milla', 'mile'],
    ['nmi', 1852, 'millas náuticas', 'milla nautica', 'nautical mile'],
  ],
  peso: [
    ['mg', 1e-6, 'miligramos', 'miligramo'],
    ['g', 0.001, 'gramos', 'gramo', 'gr', 'gram', 'grs'],
    ['kg', 1, 'kilogramos', 'kilogramo', 'kilo', 'kilos', 'kilogram', 'kgs'],
    ['t', 1000, 'toneladas', 'tonelada', 'ton', 'tonne'],
    ['oz', 0.028349523125, 'onzas', 'onza', 'ounce'],
    ['lb', 0.45359237, 'libras', 'libra', 'pound', 'lbs'],
    ['st', 6.35029318, 'stones', 'stone'],
  ],
  volumen: [
    ['ml', 0.001, 'mililitros', 'mililitro', 'cc', 'cm3'],
    ['cl', 0.01, 'centilitros', 'centilitro'],
    ['l', 1, 'litros', 'litro', 'liter', 'litre', 'lt', 'lts'],
    ['m³', 1000, 'metros cúbicos', 'm3', 'metro cubico'],
    ['cdta', 0.00492892159375, 'cucharaditas', 'cucharadita', 'tsp', 'teaspoon'],
    ['cda', 0.01478676478125, 'cucharadas', 'cucharada', 'tbsp', 'tablespoon'],
    ['taza', 0.2365882365, 'tazas', 'cup', 'cups'],
    ['fl oz', 0.0295735295625, 'onzas líquidas', 'floz', 'onza liquida', 'fluid ounce'],
    ['pt', 0.473176473, 'pintas', 'pinta', 'pint'],
    ['gal', 3.785411784, 'galones', 'galon', 'gallon'],
  ],
  área: [
    ['cm²', 1e-4, 'centímetros cuadrados', 'cm2', 'centimetro cuadrado'],
    ['m²', 1, 'metros cuadrados', 'm2', 'metro cuadrado'],
    ['km²', 1e6, 'kilómetros cuadrados', 'km2', 'kilometro cuadrado'],
    ['ha', 1e4, 'hectáreas', 'hectarea', 'hectare'],
    ['ft²', 0.09290304, 'pies cuadrados', 'ft2', 'pie cuadrado', 'sqft'],
    ['acre', 4046.8564224, 'acres'],
  ],
  velocidad: [
    ['m/s', 1, 'metros por segundo', 'mps'],
    ['km/h', 1 / 3.6, 'kilómetros por hora', 'kmh', 'kph', 'kmph'],
    ['mph', 0.44704, 'millas por hora'],
    ['kn', 1852 / 3600, 'nudos', 'nudo', 'knot', 'kt'],
  ],
  tiempo: [
    ['ms', 0.001, 'milisegundos', 'milisegundo'],
    ['s', 1, 'segundos', 'segundo', 'seg', 'sec', 'second'],
    ['min', 60, 'minutos', 'minuto', 'minute', 'mins'],
    ['h', 3600, 'horas', 'hora', 'hr', 'hour', 'hrs'],
    ['d', 86400, 'días', 'dia', 'day'],
    ['sem', 604800, 'semanas', 'semana', 'week', 'wk'],
    ['año', 31557600, 'años', 'ano', 'year', 'yr'],
  ],
  datos: [
    ['bit', 0.125, 'bits'],
    ['B', 1, 'bytes', 'byte', 'b'],
    ['KB', 1e3, 'kilobytes', 'kilobyte', 'kb'],
    ['MB', 1e6, 'megabytes', 'megabyte', 'mb'],
    ['GB', 1e9, 'gigabytes', 'gigabyte', 'gb'],
    ['TB', 1e12, 'terabytes', 'terabyte', 'tb'],
    ['KiB', 1024, 'kibibytes', 'kib'],
    ['MiB', 1048576, 'mebibytes', 'mib'],
    ['GiB', 1073741824, 'gibibytes', 'gib'],
  ],
  energía: [
    ['J', 1, 'julios', 'julio', 'joule', 'j'],
    ['kJ', 1000, 'kilojulios', 'kilojulio', 'kj'],
    ['cal', 4.184, 'calorías', 'caloria'],
    ['kcal', 4184, 'kilocalorías', 'kilocaloria'],
    ['Wh', 3600, 'vatios hora', 'wh'],
    ['kWh', 3.6e6, 'kilovatios hora', 'kwh'],
  ],
};

// La temperatura: a kelvin y de kelvin.
var TEMPERATURA = [
  ['°C', function (x) { return x + 273.15; }, function (k) { return k - 273.15; }, 'grados Celsius', 'c', 'celsius', 'centigrado', 'centigrados', 'ºc'],
  ['°F', function (x) { return (x - 32) * 5 / 9 + 273.15; }, function (k) { return (k - 273.15) * 9 / 5 + 32; }, 'grados Fahrenheit', 'f', 'fahrenheit', 'ºf'],
  ['K', function (x) { return x; }, function (k) { return k; }, 'kelvin', 'k', 'kelvins'],
];

function sinAcentos(s) {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '');
}

// Cada alias (en minusculas y sin acentos) -> su unidad.
var POR_NOMBRE = {};
function anotar(u, nombres) {
  nombres.forEach(function (n) {
    var k = sinAcentos(String(n).toLowerCase());
    if (!(k in POR_NOMBRE)) POR_NOMBRE[k] = u;
  });
}
Object.keys(TABLA).forEach(function (dim) {
  TABLA[dim].forEach(function (f) {
    var u = { dim: dim, sim: f[0], factor: f[1], nombre: f[2] };
    anotar(u, [f[0], f[2]].concat(f.slice(3)));
  });
});
TEMPERATURA.forEach(function (f) {
  var u = { dim: 'temperatura', sim: f[0], aK: f[1], deK: f[2], nombre: f[3] };
  anotar(u, [f[0], f[3]].concat(f.slice(4)));
});
var POR_DIMENSION = {};
Object.keys(POR_NOMBRE).forEach(function (k) {
  var u = POR_NOMBRE[k], l = POR_DIMENSION[u.dim] = POR_DIMENSION[u.dim] || [];
  if (l.indexOf(u) < 0) l.push(u);
});

function unidad(texto) {
  var t = sinAcentos(texto.trim().toLowerCase()).replace(/\.$/, '').replace(/^°/, '');
  if (!t) return null;
  if (t in POR_NOMBRE) return POR_NOMBRE[t];
  if (('°' + t) in POR_NOMBRE) return POR_NOMBRE['°' + t];
  // Plurales que no estan en la tabla.
  var sin = t.replace(/es$/, '');
  if (sin !== t && sin in POR_NOMBRE) return POR_NOMBRE[sin];
  sin = t.replace(/s$/, '');
  return sin !== t && sin in POR_NOMBRE ? POR_NOMBRE[sin] : null;
}

function convertir(x, de, a) {
  if (de.dim === 'temperatura') return a.deK(de.aK(x));
  return x * de.factor / a.factor;
}

function formato(x, coma) {
  if (!isFinite(x)) return String(x);
  var s = String(parseFloat(x.toPrecision(7)));
  return coma ? s.replace('.', ',') : s;
}

var CONECTORES = /^(a|en|to|in|como|->|=|→)\s+/;

// `10 km a mi` -> { x: 10, de: <km>, a: <mi> | null }.
function leer(busqueda) {
  var m = /^\s*(-?\d+(?:[.,]\d+)?(?:e[-+]?\d+)?)\s*(.*)$/i.exec(busqueda);
  if (!m) return null;
  var coma = m[1].indexOf(',') >= 0;
  var x = parseFloat(m[1].replace(',', '.'));
  var resto = m[2].replace(/\s*(->|→|=)\s*/g, ' a ').trim();
  if (!resto) return null;
  var palabras = resto.split(/\s+/);
  // La unidad de origen mas larga que encaje (`millas nauticas`, `fl oz`).
  for (var i = palabras.length; i > 0; i--) {
    var de = unidad(palabras.slice(0, i).join(' '));
    if (!de) continue;
    var destino = palabras.slice(i).join(' ').replace(CONECTORES, '');
    var a = destino ? unidad(destino) : null;
    if (destino && (!a || a.dim !== de.dim)) return { x: x, de: de, a: null, coma: coma, malo: destino };
    return { x: x, de: de, a: a, coma: coma };
  }
  return null;
}

function usos() {
  return Flow.storage.get('usos') || {};
}

function resultado(c, a, puntos) {
  var valor = formato(convertir(c.x, c.de, a), c.coma);
  var texto = formato(c.x, c.coma) + ' ' + c.de.sim + ' = ' + valor + ' ' + a.sim;
  var clave = c.de.sim + '>' + a.sim;
  return {
    title: valor + ' ' + a.sim,
    subTitle: texto + ' (' + a.nombre + ') · toca para copiar',
    icoPath: 'Images/conversor.png',
    score: puntos + Math.min(50, (usos()[clave] || 0) * 5),
    jsonRPCAction: { method: 'copiar', parameters: [valor, clave] },
    contextData: { valor: valor, sim: a.sim, texto: texto, x: formato(c.x, c.coma), de: c.de.sim, clave: clave },
  };
}

function ayuda(titulo, sub) {
  return [{ title: titulo, subTitle: sub, icoPath: 'Images/conversor.png', score: 0 }];
}

function query(q) {
  var busqueda = (q.search || '').trim();
  if (!busqueda) {
    return ayuda('Conversor de unidades', 'Escribe p. ej. «10 km a mi», «100 f c», «5 kg» o «2 tazas en ml»');
  }
  var c = leer(busqueda);
  if (!c) return ayuda('¿Qué convierto?', 'Un número y su unidad: «10 km a mi», «72 f c», «3 lb»');
  if (c.malo) return ayuda('No sé pasar ' + c.de.sim + ' a «' + c.malo + '»', 'Prueba con otra unidad de ' + c.de.dim);
  if (c.a) return [resultado(c, c.a, 1000)];
  // Sin destino: a todas las de su tipo, las mas usadas antes.
  return POR_DIMENSION[c.de.dim]
    .filter(function (a) { return a !== c.de; })
    .map(function (a, i) { return resultado(c, a, 100 - i); });
}

function context_menu(d) {
  if (!d || !d.valor) return [];
  var item = function (titulo, sub, metodo, params) {
    return { title: titulo, subTitle: sub, icoPath: 'Images/conversor.png', jsonRPCAction: { method: metodo, parameters: params } };
  };
  return [
    item('Copiar «' + d.valor + ' ' + d.sim + '»', 'Con la unidad', 'copiar', [d.valor + ' ' + d.sim, d.clave]),
    item('Copiar «' + d.texto + '»', 'La conversión entera', 'copiar', [d.texto, d.clave]),
    item('Al revés: ' + d.sim + ' → ' + d.de, 'Convertir ' + d.valor + ' ' + d.sim + ' a ' + d.de, 'invertir', [d.valor, d.sim, d.de]),
  ];
}

// Acciones (el `method` de cada jsonRPCAction).
function copiar(texto, clave) {
  Flow.copyToClipboard(texto);
  if (clave) {
    var u = usos();
    u[clave] = (u[clave] || 0) + 1;
    Flow.storage.set('usos', u);
  }
}

function invertir(valor, de, a) {
  var palabra = (Flow.metadata && Flow.metadata.actionKeyword) || 'cv';
  Flow.changeQuery(palabra + ' ' + valor + ' ' + de + ' a ' + a, true);
  return false; // que no se cierre el buscador
}
