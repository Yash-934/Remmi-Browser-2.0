use adblock::request::Request;

fn main() {
    let req = Request::new("https://google.com", "https://google.com", "beacon");
    println!("beacon: {:?}", req.is_ok());
    let req2 = Request::new("https://google.com", "https://google.com", "ping");
    println!("ping: {:?}", req2.is_ok());
}
