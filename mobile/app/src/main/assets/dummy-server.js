const http = require('http');

const PORT = 8000;

const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end('<h1>Hello from stAPK Node Runtime!</h1><p>The dummy server is running successfully on Android.</p>');
});

server.listen(PORT, '127.0.0.1', () => {
    console.log(`Dummy server listening on http://127.0.0.1:${PORT}`);
});
