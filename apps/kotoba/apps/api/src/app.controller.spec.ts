import { Test, TestingModule } from '@nestjs/testing';
import { AppController } from './app.controller';
import { AppService } from './app.service';
import { vi, describe, it, expect, beforeEach } from 'vitest';

const mockAppService = {
  getHello: vi.fn(),
};

describe('AppController', () => {
  let appController: AppController;
  let appService: AppService;

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      controllers: [AppController],
      providers: [
        {
          provide: AppService,
          useValue: mockAppService,
        },
      ],
    }).compile();

    appController = module.get<AppController>(AppController);
    appService = module.get<AppService>(AppService);
  });

  describe('root', () => {
    it.skip('should return "Hello World!"', () => {
      const result = 'Hello World!';
      mockAppService.getHello.mockReturnValue(result);
      expect(appController.getHello()).toBe(result);
    });
  });
});
