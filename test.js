import http from 'k6/http';

export const options = {
  scenarios: {
    sms_load: {
      executor: 'constant-arrival-rate',
      rate: 333,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 500,
      maxVUs: 1000,
    },
  },
};

export default function () {
  const payload = JSON.stringify({
    from: 'DUMMY',
    to: ['918999614816'],
    text: 'Test message from GTS Staging using GTS adapter',
    type: 'N',
    product: 'Transactional',
    entity_id: '1701164872369547174',
    template_id: '150245188685280',
  });

  http.post(
    'https://testqa.gtsstaging.com/api/sms/send',
    payload,
    {
      headers: {
        'Content-Type': 'application/json',
      },
    }
  );
}